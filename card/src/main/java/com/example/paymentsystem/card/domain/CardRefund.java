package com.example.paymentsystem.card.domain;

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
@Table(name = "card_refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false, length = 100, unique = true)
    private String refundId;

    @Column(name = "card_request_ref", nullable = false, length = 100, unique = true)
    private String cardRequestRef;

    @Column(name = "capture_id", nullable = false, length = 100)
    private String captureId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardRefundStatus status;

    @Column(name = "refunded_at", nullable = false)
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CardRefund(String refundId, String cardRequestRef, String captureId, Long amount, CardRefundStatus status, Instant refundedAt) {
        this.refundId = refundId;
        this.cardRequestRef = cardRequestRef;
        this.captureId = captureId;
        this.amount = amount;
        this.status = status;
        this.refundedAt = refundedAt;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
