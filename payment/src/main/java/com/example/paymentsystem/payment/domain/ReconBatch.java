package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recon_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconBatch {

    private static final int ABORT_MESSAGE_MAX_LENGTH = 65000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_company", nullable = false, length = 50)
    private String cardCompany;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconBatchStatus status;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    @Column(name = "file_total_amount", nullable = false)
    private Long fileTotalAmount;

    @Column(name = "ingestion_failed_count", nullable = false)
    private Integer ingestionFailedCount;

    @Column(name = "discrepancy_count", nullable = false)
    private Integer discrepancyCount;

    @Column(name = "auto_resolved_count", nullable = false)
    private Integer autoResolvedCount;

    @Column(name = "clearing_amount")
    private Long clearingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "abort_reason", length = 30)
    private ReconBatchAbortReason abortReason;

    @Column(name = "abort_message", columnDefinition = "TEXT")
    private String abortMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ReconBatch(String cardCompany, LocalDate businessDate) {
        if (cardCompany == null || cardCompany.isBlank()) {
            throw new IllegalArgumentException("cardCompany must not be blank");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate must not be null");
        }
        this.cardCompany = cardCompany;
        this.businessDate = businessDate;
        this.status = ReconBatchStatus.INGESTING;
        this.rowCount = 0;
        this.fileTotalAmount = 0L;
        this.ingestionFailedCount = 0;
        this.discrepancyCount = 0;
        this.autoResolvedCount = 0;
        this.startedAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markIngested(int rowCount, long fileTotalAmount, int ingestionFailedCount) {
        this.status = ReconBatchStatus.INGESTED;
        this.rowCount = rowCount;
        this.fileTotalAmount = fileTotalAmount;
        this.ingestionFailedCount = ingestionFailedCount;
    }

    public void markMatching() {
        this.status = ReconBatchStatus.MATCHING;
    }

    public void markCompleted(int discrepancyCount, Long clearingAmount, int autoResolvedCount) {
        this.status = ReconBatchStatus.COMPLETED;
        this.discrepancyCount = discrepancyCount;
        this.clearingAmount = clearingAmount;
        this.autoResolvedCount = autoResolvedCount;
        this.completedAt = Instant.now();
    }

    public void markAborted(ReconBatchAbortReason reason, String message) {
        if (reason == null) {
            throw new IllegalArgumentException("abort reason must not be null");
        }
        this.status = ReconBatchStatus.ABORTED;
        this.abortReason = reason;
        this.abortMessage = truncate(message);
        this.completedAt = Instant.now();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ABORT_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ABORT_MESSAGE_MAX_LENGTH);
    }
}
