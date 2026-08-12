package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.*;

@Entity
@Table(
        name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_keys_operation_key",
                columnNames = {"operation", "idempotent_key"}
        ),
        indexes = @Index(name = "idx_idempotency_keys_expire_at", columnList = "expire_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IdempotencyOperation operation;

    @Column(name = "idempotent_key", nullable = false)
    private String idempotentKey;

    // 키 자체가 요청을 온전히 규정하는 operation(승인)은 비워 둔다 — 지문 찍을 추가 본문이 없다.
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotentStatus status;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.expireAt = now.plus(24, ChronoUnit.HOURS);
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // 상태 가드 — 이미 확정된 응답은 덮어쓰지 않는다.
    // 동기 흐름 / inquiry / 크래시 복구가 같은 키를 두고 경쟁해도 최초 확정값이 유지된다.
    public void complete(int responseCode, String responseBody) {
        if (this.status == IdempotentStatus.COMPLETE) {
            return;
        }
        this.status = IdempotentStatus.COMPLETE;
        this.responseCode = responseCode;
        this.responseBody = responseBody;
    }
}
