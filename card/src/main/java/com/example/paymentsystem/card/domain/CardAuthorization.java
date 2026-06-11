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
import jakarta.persistence.Version;
import java.time.Instant;

import lombok.*;

@Entity
@Table(name = "card_authorization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class CardAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_id", nullable = false, length = 100, unique = true)
    private String authId;

    @Column(name = "auth_idempotent_key", nullable = false, length = 150, unique = true)
    private String authIdempotentKey;

    @Column(name = "auth_hash", nullable = false, length = 64)
    private String authHash;

    @Column(name = "capture_idempotent_key", length = 150, unique = true)
    private String captureIdempotentKey;

    @Column(name = "capture_id", length = 100, unique = true)
    private String captureId;

    @Column(name = "capture_hash", length = 64)
    private String captureHash;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_status", nullable = false, length = 30)
    private CardAuthStatus authStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_status", nullable = false, length = 30)
    private CardCaptureStatus captureStatus;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "capture_card_request_ref", length = 100)
    private String captureCardRequestRef;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

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

    public void capture(String captureId, String captureIdempotentKey, String captureHash, String captureCardRequestRef, Instant capturedAt) {
        this.captureId = captureId;
        this.captureIdempotentKey = captureIdempotentKey;
        this.captureHash = captureHash;
        this.captureCardRequestRef = captureCardRequestRef;
        this.captureStatus = CardCaptureStatus.SUCCESS;
        this.capturedAt = capturedAt;
    }

    public void markVoided() {
        if (this.captureStatus == CardCaptureStatus.SUCCESS) {
            throw new IllegalStateException("Cannot void: already captured");
        }
        this.authStatus = CardAuthStatus.VOIDED;
    }
}
