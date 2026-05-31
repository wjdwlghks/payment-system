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
        this.startedAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markIngested(int rowCount, long fileTotalAmount) {
        this.status = ReconBatchStatus.INGESTED;
        this.rowCount = rowCount;
        this.fileTotalAmount = fileTotalAmount;
    }

    public void markIngestedPartial(int rowCount, long fileTotalAmount) {
        this.status = ReconBatchStatus.INGESTED_PARTIAL;
        this.rowCount = rowCount;
        this.fileTotalAmount = fileTotalAmount;
    }

    public void markMatching() {
        this.status = ReconBatchStatus.MATCHING;
    }

    public void markCompleted() {
        this.status = ReconBatchStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markAborted() {
        this.status = ReconBatchStatus.ABORTED;
        this.completedAt = Instant.now();
    }
}
