package com.example.paymentsystem.payment.domain;

import com.example.paymentsystem.payment.dto.ParsedSettlementRow;
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
@Table(name = "staging_failed")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StagingFailed {

    private static final int FAILURE_REASON_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recon_batch_id", nullable = false)
    private ReconBatch reconBatch;

    @Column(name = "approval_no", nullable = false, length = 100)
    private String approvalNo;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "transacted_at", nullable = false)
    private Instant transactedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 16)
    private SettlementType txType;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_status", nullable = false, length = 16)
    private SettlementStatus txStatus;

    @Column(name = "original_approval_no", length = 100)
    private String originalApprovalNo;

    @Column(name = "failure_reason", nullable = false, length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public StagingFailed(ReconBatch reconBatch, ParsedSettlementRow row, String failureReason) {
        if (reconBatch == null) {
            throw new IllegalArgumentException("reconBatch must not be null");
        }
        if (row == null) {
            throw new IllegalArgumentException("row must not be null");
        }
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("failureReason must not be blank");
        }
        this.reconBatch = reconBatch;
        this.approvalNo = row.approvalNo();
        this.amount = row.amount();
        this.transactedAt = row.transactedAt();
        this.txType = row.txType();
        this.txStatus = row.txStatus();
        this.originalApprovalNo = row.originalApprovalNo();
        this.failureReason = truncate(failureReason);
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    private static String truncate(String reason) {
        if (reason.length() <= FAILURE_REASON_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
