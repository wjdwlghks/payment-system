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
@Table(name = "payout")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_code", nullable = false, length = 32, unique = true)
    private String payoutCode;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    public Payout(
            String payoutCode,
            String merchantId,
            Long amount
    ) {
        validateAmount(amount);
        this.payoutCode = payoutCode;
        this.merchantId = merchantId;
        this.status = PayoutStatus.CREATED;
        this.amount = amount;
    }

    @PrePersist
    void prePersist() {
        this.requestedAt = Instant.now();
    }

    public void markPaid() {
        this.status = PayoutStatus.PAID;
        this.paidAt = Instant.now();
    }

    public void markFailed() {
        this.status = PayoutStatus.FAILED;
    }

    private void validateAmount(Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
