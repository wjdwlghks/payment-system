package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "webhook_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Lob
    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WebhookOutboxStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Lob
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public WebhookOutbox(
            String eventId,
            String aggregateId,
            String eventType,
            String payload,
            Instant nextAttemptAt
    ) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = WebhookOutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = nextAttemptAt;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markSent() {
        this.status = WebhookOutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    public void markRetry(Instant nextAttemptAt, String lastError) {
        this.status = WebhookOutboxStatus.PENDING;
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
    }

    public void markDead(String lastError) {
        this.status = WebhookOutboxStatus.DEAD;
        this.lastError = lastError;
    }
}
