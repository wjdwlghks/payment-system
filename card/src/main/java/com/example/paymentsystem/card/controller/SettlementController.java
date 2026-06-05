package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import com.example.paymentsystem.card.domain.CardRefund;
import com.example.paymentsystem.card.domain.CardRefundStatus;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import com.example.paymentsystem.card.repository.CardRefundRepository;
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

@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private static final String HEADER = "card_request_ref,approval_no,amount,transacted_at,tx_type,tx_status,original_approval_no";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final CardAuthorizationRepository authorizationRepository;
    private final CardRefundRepository refundRepository;

    @Value("${settlement.output-dir:/recon-files}")
    private String outputDir;

    @PostMapping("/generate")
    public ResponseEntity<String> generate() {
        String filename = "settlement-" + FILE_TS.format(Instant.now()) + ".csv";
        Path path = Path.of(outputDir, filename);

        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                writer.write(HEADER);
                writer.newLine();

                writeCaptures(writer);
                writeRefunds(writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return ResponseEntity.ok(filename);
    }

    private void writeCaptures(BufferedWriter writer) throws IOException {
        List<CardAuthorization> captures = authorizationRepository.findByCaptureStatusIn(
                List.of(CardCaptureStatus.SUCCESS, CardCaptureStatus.FAILED)
        );

        for (CardAuthorization auth : captures) {
            String txStatus = auth.getCaptureStatus() == CardCaptureStatus.SUCCESS ? "APPROVED" : "DECLINED";
            writeLine(writer,
                    auth.getCaptureCardRequestRef(),
                    auth.getCaptureId(),
                    auth.getAmount(),
                    auth.getCapturedAt(),
                    "CAPTURE",
                    txStatus,
                    ""
            );
        }
    }

    private void writeRefunds(BufferedWriter writer) throws IOException {
        List<CardRefund> refunds = refundRepository.findAll();

        for (CardRefund refund : refunds) {
            String txStatus = refund.getStatus() == CardRefundStatus.SUCCESS ? "APPROVED" : "DECLINED";
            writeLine(writer,
                    refund.getCardRequestRef(),
                    refund.getRefundId(),
                    refund.getAmount(),
                    refund.getRefundedAt(),
                    "REFUND",
                    txStatus,
                    refund.getCaptureId()
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
