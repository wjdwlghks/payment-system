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
import java.util.UUID;

import lombok.*;

@Entity
@Table(name = "`transaction`")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_intent_id", nullable = false)
    private PaymentIntent paymentIntent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionStatus status;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "idempotent_key")
    private String idempotentKey;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "card_request_ref", nullable = false, length = 100, updatable = false)
    private String cardRequestRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentTransaction(
            PaymentIntent paymentIntent,
            TransactionType type,
            Long amount,
            String idempotentKey
    ) {
        this.paymentIntent = paymentIntent;
        this.type = type;
        this.amount = amount;
        this.idempotentKey = idempotentKey;
        this.status = TransactionStatus.REQUESTED;
        this.cardRequestRef = "pg-" + UUID.randomUUID();
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
        this.status = TransactionStatus.SUCCEEDED;
        this.externalId = externalId;
    }

    public void markFail(String externalId) {
        this.status = TransactionStatus.FAIL;
        this.externalId = externalId;
    }

    public void markFailWithoutResponse() {
        this.status = TransactionStatus.FAIL;
    }

    public void markUnknown() {
        this.status = TransactionStatus.UNKNOWN;
    }
}
