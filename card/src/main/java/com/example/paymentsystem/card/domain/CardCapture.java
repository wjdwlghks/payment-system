package com.example.paymentsystem.card.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** 매입 — 승인건에 대한 대금 청구. 승인(card_authentication)과 1:N이 아니라 사실상 1:1이다. */
@Entity
@Table(name = "card_capture")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class CardCapture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capture_id", nullable = false, length = 100, unique = true)
    private String captureId;

    @Column(name = "approval_id", nullable = false, length = 100)
    private String approvalId;

    @Column(name = "card_request_ref", nullable = false, length = 100, unique = true)
    private String cardRequestRef;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CardCaptureStatus status;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
