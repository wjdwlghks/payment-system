package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_intent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false, length = 64, unique = true)
    private String paymentKey;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentIntentStatus status;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "capture_id", length = 100)
    private String captureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_company", nullable = false, length = 30)
    private CardCompany cardCompany;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentIntent(String paymentKey, String orderId, String merchantId, Long amount, CardCompany cardCompany) {
        this.paymentKey = paymentKey;
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.status = PaymentIntentStatus.AUTH_REQUESTED;
        this.cardCompany = cardCompany;
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

    public void markAuthReady(Instant authorizedAt) {
        this.status = PaymentIntentStatus.AUTH_READY;
        this.authorizedAt = authorizedAt;
    }

    public void markAuthFailed() {
        this.status = PaymentIntentStatus.AUTH_FAILED;
    }

    public void markFdsRequested() {
        if (this.status != PaymentIntentStatus.AUTH_READY) {
            throw new IllegalStateException("Cannot start FDS: intent status is " + this.status);
        }
        this.status = PaymentIntentStatus.FDS_REQUESTED;
    }

    public void markFdsFailed() {
        this.status = PaymentIntentStatus.FDS_FAILED;
    }

    public void markFdsReady() {
        this.status = PaymentIntentStatus.FDS_READY;
    }

    public void markCaptureRequested() {
        if (this.status != PaymentIntentStatus.FDS_READY) {
            throw new IllegalStateException("Cannot start capture: intent status is " + this.status);
        }
        this.status = PaymentIntentStatus.CAPTURE_REQUESTED;
    }

    public void markCaptureFailed() {
        this.status = PaymentIntentStatus.CAPTURE_FAILED;
    }

    public void markDone(String captureId) {
        this.status = PaymentIntentStatus.DONE;
        this.captureId = captureId;
    }

    public void markAuthUnknown() {
        this.status = PaymentIntentStatus.UNKNOWN_AUTH;
    }

    public void markFdsUnknown() {
        this.status = PaymentIntentStatus.UNKNOWN_FDS;
    }

    public void markCaptureUnknown() {
        this.status = PaymentIntentStatus.UNKNOWN_CAPTURE;
    }


}
