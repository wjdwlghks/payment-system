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
@Table(name = "staging_settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StagingSettlement {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 16)
    private SettlementMatchStatus matchStatus;

    @Column(name = "matched_tx_id")
    private Long matchedTxId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public StagingSettlement(
            ReconBatch reconBatch,
            String approvalNo,
            Long amount,
            Instant transactedAt,
            SettlementType txType,
            SettlementStatus txStatus,
            String originalApprovalNo
    ) {
        if (reconBatch == null) {
            throw new IllegalArgumentException("reconBatch must not be null");
        }
        if (approvalNo == null || approvalNo.isBlank()) {
            throw new IllegalArgumentException("approvalNo must not be blank");
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (transactedAt == null) {
            throw new IllegalArgumentException("transactedAt must not be null");
        }
        if (txType == null) {
            throw new IllegalArgumentException("txType must not be null");
        }
        if (txStatus == null) {
            throw new IllegalArgumentException("txStatus must not be null");
        }
        if (txType == SettlementType.REFUND && (originalApprovalNo == null || originalApprovalNo.isBlank())) {
            throw new IllegalArgumentException("originalApprovalNo must be present for REFUND rows");
        }
        this.reconBatch = reconBatch;
        this.approvalNo = approvalNo;
        this.amount = amount;
        this.transactedAt = transactedAt;
        this.txType = txType;
        this.txStatus = txStatus;
        this.originalApprovalNo = originalApprovalNo;
        this.matchStatus = SettlementMatchStatus.NEW;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markMatched(Long matchedTxId) {
        if (matchedTxId == null) {
            throw new IllegalArgumentException("matchedTxId must not be null");
        }
        this.matchStatus = SettlementMatchStatus.MATCHED;
        this.matchedTxId = matchedTxId;
    }

    public void markUnmatched() {
        this.matchStatus = SettlementMatchStatus.UNMATCHED;
    }
}
