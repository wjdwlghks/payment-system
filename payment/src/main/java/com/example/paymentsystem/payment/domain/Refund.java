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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_key", nullable = false, length = 64, unique = true)
    private String refundKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_intent_id", nullable = false)
    private PaymentIntent paymentIntent;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RefundStatus status;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Refund(PaymentIntent paymentIntent, Long transactionId, String refundKey, Long amount) {
        if (refundKey == null || refundKey.isBlank()) {
            throw new IllegalArgumentException("refundKey must not be blank");
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId must not be null");
        }
        this.refundKey = refundKey;
        this.paymentIntent = paymentIntent;
        this.transactionId = transactionId;
        this.amount = amount;
        this.status = RefundStatus.REQUESTED;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markSucceeded(String externalId) {
        this.status = RefundStatus.SUCCEEDED;
        this.externalId = externalId;
    }

    public void markFail(String externalId) {
        this.status = RefundStatus.FAIL;
        this.externalId = externalId;
    }

    public void markFailWithoutResponse() {
        this.status = RefundStatus.FAIL;
    }

    public void markUnknown() {
        this.status = RefundStatus.UNKNOWN;
    }
}
