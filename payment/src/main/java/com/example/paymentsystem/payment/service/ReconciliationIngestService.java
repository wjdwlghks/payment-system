package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.ReconBatch;
import com.example.paymentsystem.payment.domain.ReconBatchAbortReason;
import com.example.paymentsystem.payment.domain.ReconBatchStatus;
import com.example.paymentsystem.payment.domain.SettlementStatus;
import com.example.paymentsystem.payment.domain.SettlementType;
import com.example.paymentsystem.payment.dto.IngestReconciliationRequest;
import com.example.paymentsystem.payment.dto.IngestReconciliationResponse;
import com.example.paymentsystem.payment.dto.ParsedSettlementRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReconciliationIngestService {

    private static final String EXPECTED_HEADER =
            "card_request_ref,approval_no,amount,transacted_at,tx_type,tx_status,original_approval_no";
    private static final int FIELD_COUNT = 7;
    private static final int FLUSH_CHUNK = 500;
    private static final int MAX_ATTEMPTS = 3;

    private final ChunkProcessor chunkProcessor;

    public IngestReconciliationResponse ingest(IngestReconciliationRequest request) {
        Path path = resolvePath(request.filePath());
        ReconBatch batch = chunkProcessor.createBatch(request.cardCompany(), request.businessDate());
        Long batchId = batch.getId();

        IngestStats stats;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !header.trim().equals(EXPECTED_HEADER)) {
                throw new IllegalArgumentException(
                        "CSV header mismatch. expected=[" + EXPECTED_HEADER + "] actual=[" + header + "]"
                );
            }
            stats = streamRows(batchId, reader);
        } catch (IOException e) {
            chunkProcessor.markAborted(batchId, ReconBatchAbortReason.FILE_READ_ERROR, e.getMessage());
            throw new UncheckedIOException("failed to read reconciliation file: " + path, e);
        } catch (IllegalArgumentException e) {
            chunkProcessor.markAborted(batchId, ReconBatchAbortReason.HEADER_MISMATCH, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            chunkProcessor.markAborted(batchId, ReconBatchAbortReason.OTHER, e.getMessage());
            throw e;
        }

        chunkProcessor.markIngested(batchId, stats.rowCount(), stats.totalAmount(), stats.ingestionFailedCount());

        return new IngestReconciliationResponse(
                batchId,
                request.cardCompany(),
                request.businessDate().toString(),
                stats.rowCount(),
                stats.totalAmount(),
                stats.ingestionFailedCount(),
                ReconBatchStatus.INGESTED.name()
        );
    }

    private IngestStats streamRows(Long batchId, BufferedReader reader) throws IOException {
        List<ParsedSettlementRow> buffer = new ArrayList<>(FLUSH_CHUNK);
        int rowCount = 0;
        long totalAmount = 0;
        int ingestionFailedCount = 0;

        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            ParsedSettlementRow row = parseRow(line, lineNumber);
            buffer.add(row);
            rowCount++;
            totalAmount += row.amount();

            if (buffer.size() >= FLUSH_CHUNK) {
                ingestionFailedCount += processChunk(batchId, buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            ingestionFailedCount += processChunk(batchId, buffer);
            buffer.clear();
        }

        return new IngestStats(rowCount, totalAmount, ingestionFailedCount);
    }

    private int processChunk(Long batchId, List<ParsedSettlementRow> buffer) {
        List<ParsedSettlementRow> snapshot = List.copyOf(buffer);
        TransientDataAccessException lastTransient = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                chunkProcessor.saveChunk(batchId, snapshot);
                return 0;
            } catch (TransientDataAccessException e) {
                lastTransient = e;
            } catch (Exception e) {
                return rowByRowFallback(batchId, snapshot);
            }
        }
        chunkProcessor.quarantineRows(batchId, snapshot, formatReason(lastTransient));
        return snapshot.size();
    }

    private int rowByRowFallback(Long batchId, List<ParsedSettlementRow> rows) {
        int failed = 0;
        for (ParsedSettlementRow row : rows) {
            if (!saveOneWithRetry(batchId, row)) {
                failed++;
            }
        }
        return failed;
    }

    private boolean saveOneWithRetry(Long batchId, ParsedSettlementRow row) {
        TransientDataAccessException lastTransient = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                chunkProcessor.saveOne(batchId, row);
                return true;
            } catch (TransientDataAccessException e) {
                lastTransient = e;
            } catch (Exception e) {
                chunkProcessor.quarantineOne(batchId, row, formatReason(e));
                return false;
            }
        }
        chunkProcessor.quarantineOne(batchId, row, formatReason(lastTransient));
        return false;
    }

    private ParsedSettlementRow parseRow(String line, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "CSV row at line " + lineNumber + " has " + fields.length
                            + " fields, expected " + FIELD_COUNT
            );
        }

        String cardRequestRef = fields[0].trim();
        String approvalNo = fields[1].trim();
        long amount = parseLong(fields[2], "amount", lineNumber);
        Instant transactedAt = parseInstant(fields[3], "transacted_at", lineNumber);
        SettlementType txType = parseEnum(fields[4], SettlementType.class, "tx_type", lineNumber);
        SettlementStatus txStatus = parseEnum(fields[5], SettlementStatus.class, "tx_status", lineNumber);
        String originalApprovalNo = fields[6].trim().isEmpty() ? null : fields[6].trim();

        return new ParsedSettlementRow(
                cardRequestRef,
                approvalNo,
                amount,
                transactedAt,
                txType,
                txStatus,
                originalApprovalNo
        );
    }

    private Path resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath must not be blank");
        }
        Path path = Paths.get(filePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("file not found or not a regular file: " + path);
        }
        return path;
    }

    private long parseLong(String raw, String field, int lineNumber) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid " + field + " at line " + lineNumber + ": [" + raw + "]"
            );
        }
    }

    private Instant parseInstant(String raw, String field, int lineNumber) {
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "invalid " + field + " at line " + lineNumber + ": [" + raw + "]"
            );
        }
    }

    private <E extends Enum<E>> E parseEnum(
            String raw, Class<E> type, String field, int lineNumber
    ) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid " + field + " at line " + lineNumber + ": [" + raw + "]"
            );
        }
    }

    private static String formatReason(Throwable t) {
        String name = t.getClass().getName();
        String msg = t.getMessage() == null ? "" : t.getMessage();
        return name + ": " + msg;
    }

    private record IngestStats(int rowCount, long totalAmount, int ingestionFailedCount) {
    }
}
