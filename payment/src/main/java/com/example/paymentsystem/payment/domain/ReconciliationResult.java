package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reconciliation_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationResult {

    private static final int VALUE_MAX_LENGTH = 255;
    private static final int NOTES_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recon_batch_id", nullable = false)
    private ReconBatch reconBatch;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 30)
    private ReconciliationCaseType caseType;

    @Column(name = "staging_settlement_id")
    private Long stagingSettlementId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "expected_value", length = VALUE_MAX_LENGTH)
    private String expectedValue;

    @Column(name = "actual_value", length = VALUE_MAX_LENGTH)
    private String actualValue;

    @Column(length = NOTES_MAX_LENGTH)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private ReconciliationResult(
            ReconBatch reconBatch,
            ReconciliationCaseType caseType,
            Long stagingSettlementId,
            Long transactionId,
            String expectedValue,
            String actualValue,
            String notes
    ) {
        this.reconBatch = reconBatch;
        this.caseType = caseType;
        this.stagingSettlementId = stagingSettlementId;
        this.transactionId = transactionId;
        this.expectedValue = truncate(expectedValue, VALUE_MAX_LENGTH);
        this.actualValue = truncate(actualValue, VALUE_MAX_LENGTH);
        this.notes = truncate(notes, NOTES_MAX_LENGTH);
    }

    public static ReconciliationResult missingOnCard(ReconBatch batch, Long txId, long txAmount) {
        return new ReconciliationResult(
                batch, ReconciliationCaseType.MISSING_ON_CARD,
                null, txId,
                Long.toString(txAmount), null,
                "TX SUCCEEDED but no matching staging row"
        );
    }

    public static ReconciliationResult missingOnPg(ReconBatch batch, Long stagingId, long fileAmount, String approvalNo) {
        return new ReconciliationResult(
                batch, ReconciliationCaseType.MISSING_ON_PG,
                stagingId, null,
                null, Long.toString(fileAmount),
                "staging APPROVED but no matching TX (approvalNo=" + approvalNo + ")"
        );
    }

    public static ReconciliationResult amountMismatch(
            ReconBatch batch, Long stagingId, Long txId, long txAmount, long fileAmount
    ) {
        return new ReconciliationResult(
                batch, ReconciliationCaseType.AMOUNT_MISMATCH,
                stagingId, txId,
                Long.toString(txAmount), Long.toString(fileAmount),
                "diff=" + (fileAmount - txAmount)
        );
    }

    public static ReconciliationResult statusMismatch(
            ReconBatch batch, Long stagingId, Long txId, String txStatus, String fileStatus
    ) {
        return new ReconciliationResult(
                batch, ReconciliationCaseType.STATUS_MISMATCH,
                stagingId, txId,
                txStatus, fileStatus,
                "tx.status=" + txStatus + " card.status=" + fileStatus
        );
    }

    public static ReconciliationResult aggregate(
            ReconBatch batch, long expectedBalance, long fileNet
    ) {
        return new ReconciliationResult(
        batch, ReconciliationCaseType.AGGREGATE,
                null, null,
                Long.toString(expectedBalance), Long.toString(fileNet),
                "balance vs file_net diff=" + (fileNet - expectedBalance)
        );
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
