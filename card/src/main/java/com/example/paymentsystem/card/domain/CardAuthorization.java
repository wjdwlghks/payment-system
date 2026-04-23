package com.example.paymentsystem.card.domain;

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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "card_authorization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_id", nullable = false, length = 100, unique = true)
    private String authId;

    @Column(name = "auth_idempotent_key", nullable = false, length = 150)
    private String authIdempotentKey;

    @Column(name = "capture_idempotency_key", length = 150)
    private String captureIdempotencyKey;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CardAuthorizationStatus status;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CardAuthorization(String authId, String paymentKey, Long amount, Instant authorizedAt) {
        this.authId = authId;
        this.authIdempotentKey = paymentKey + ":auth";
        this.amount = amount;
        this.status = CardAuthorizationStatus.AUTHORIZED;
        this.authorizedAt = authorizedAt;
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

    public void capture(String paymentKey, Instant capturedAt) {
        this.captureIdempotencyKey = paymentKey + ":capture";
        this.status = CardAuthorizationStatus.CAPTURED;
        this.capturedAt = capturedAt;
    }
}
