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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "clearing_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClearingBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_code", nullable = false, length = 32, unique = true)
    private String batchCode;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClearingBatchStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ClearingBatch(
            String batchCode,
            Instant windowStart,
            Instant windowEnd,
            Long totalAmount,
            Integer itemCount
    ) {
        validateWindow(windowStart, windowEnd);
        validateSummary(totalAmount, itemCount);
        this.batchCode = batchCode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.status = ClearingBatchStatus.CREATED;
        this.totalAmount = totalAmount;
        this.itemCount = itemCount;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markCleared() {
        this.status = ClearingBatchStatus.CLEARED;
        this.clearedAt = Instant.now();
    }

    public void markFailed() {
        this.status = ClearingBatchStatus.FAILED;
    }

    private void validateWindow(Instant windowStart, Instant windowEnd) {
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("windowStart must be before windowEnd");
        }
    }

    private void validateSummary(Long totalAmount, Integer itemCount) {
        if (totalAmount < 0) {
            throw new IllegalArgumentException("totalAmount must be zero or positive");
        }
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must be zero or positive");
        }
    }
}
