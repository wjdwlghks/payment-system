package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardAuthentication;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
import com.example.paymentsystem.card.repository.CardAuthenticationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private static final String HEADER = "card_request_ref,approval_no,amount,transacted_at,tx_type,tx_status,original_approval_no";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final CardAuthenticationRepository authenticationRepository;

    @Value("${settlement.output-dir:/recon-files}")
    private String outputDir;

    @PostMapping("/generate")
    public ResponseEntity<String> generate() {
        String filename = "settlement-" + FILE_TS.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        Path path = Path.of(outputDir, filename);

        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                writer.write(HEADER);
                writer.newLine();

                writeApprovals(writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return ResponseEntity.ok(filename);
    }

    // Stage 2 한시: 아직 매입이 없어 승인 데이터로 정산 파일을 만든다.
    // tx_type 리터럴 "CAPTURE"는 파일 포맷 계약이라 유지한다 (Stage 3에서 CardCapture 기반으로 교체).
    private void writeApprovals(BufferedWriter writer) throws IOException {
        List<CardAuthentication> captures = authenticationRepository.findByApprovalStatusIn(
                List.of(CardApprovalStatus.SUCCESS, CardApprovalStatus.FAILED)
        );

        for (CardAuthentication auth : captures) {
            String txStatus = auth.getApprovalStatus() == CardApprovalStatus.SUCCESS ? "APPROVED" : "DECLINED";
            writeLine(writer,
                    auth.getApprovalCardRequestRef(),
                    auth.getApprovalId(),
                    auth.getAmount(),
                    auth.getApprovedAt(),
                    "CAPTURE",
                    txStatus,
                    ""
            );
        }
    }

    private void writeLine(BufferedWriter writer, String cardRequestRef, String approvalNo,
                           Long amount, Instant transactedAt, String txType, String txStatus,
                           String originalApprovalNo) throws IOException {
        writer.write(String.join(",",
                cardRequestRef,
                approvalNo,
                amount.toString(),
                ISO.format(transactedAt),
                txType,
                txStatus,
                originalApprovalNo
        ));
        writer.newLine();
    }
}
