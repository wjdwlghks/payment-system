package com.example.paymentsystem.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매입 배치 1회 실행 기록. admin 엔드포인트 호출 하나가 배치 하나다.
 * 청산 단위는 아니다 — UNKNOWN 매입이 나중에 확정될 수 있어, 청산은 미청산 매입 tx를 스캔한다.
 */
@Entity
@Table(name = "capture_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_code", nullable = false, length = 32, unique = true)
    private String batchCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaptureBatchStatus status;

    @Column(name = "attempted_count", nullable = false)
    private Integer attemptedCount;

    @Column(name = "succeeded_count", nullable = false)
    private Integer succeededCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    @Column(name = "unknown_count", nullable = false)
    private Integer unknownCount;

    @Column(name = "succeeded_amount", nullable = false)
    private Long succeededAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public CaptureBatch(String batchCode) {
        this.batchCode = batchCode;
        this.status = CaptureBatchStatus.RUNNING;
        this.attemptedCount = 0;
        this.succeededCount = 0;
        this.failedCount = 0;
        this.unknownCount = 0;
        this.succeededAmount = 0L;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markCompleted(int attempted, int succeeded, int failed, int unknown, long succeededAmount) {
        this.attemptedCount = attempted;
        this.succeededCount = succeeded;
        this.failedCount = failed;
        this.unknownCount = unknown;
        this.succeededAmount = succeededAmount;
        this.status = CaptureBatchStatus.COMPLETED;
        this.completedAt = Instant.now();
    }
}
