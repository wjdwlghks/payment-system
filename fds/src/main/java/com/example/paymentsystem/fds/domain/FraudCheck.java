package com.example.paymentsystem.fds.domain;

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
@Table(name = "fraud_check")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "fds_id", nullable = false, length = 100, unique = true)
    private String fdsId;

    @Column(nullable = false, length = 64)
    private String hash;

    @Column(name = "payment_key", nullable = false, length = 100)
    private String paymentKey;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FraudDecision decision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FraudCheck(String fdsId, String hash, String paymentKey, Long amount, FraudDecision decision) {
        this.idempotencyKey = paymentKey + ":fds";
        this.fdsId = fdsId;
        this.hash = hash;
        this.paymentKey = paymentKey;
        this.amount = amount;
        this.decision = decision;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
