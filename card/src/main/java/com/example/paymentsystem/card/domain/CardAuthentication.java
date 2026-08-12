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

import lombok.*;

@Entity
@Table(name = "card_authentication")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class CardAuthentication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_id", nullable = false, length = 100, unique = true)
    private String authId;

    @Column(name = "card_request_ref", nullable = false, length = 100, unique = true)
    private String cardRequestRef;

    @Column(name = "approval_id", length = 100, unique = true)
    private String approvalId;

    // 금액은 인증이 아니라 승인의 속성이다 — 승인 시점에 채워진다.
    @Column
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_status", nullable = false, length = 30)
    private CardAuthStatus authStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 30)
    private CardApprovalStatus approvalStatus;

    @Column(name = "authenticated_at", nullable = false)
    private Instant authenticatedAt;

    @Column(name = "approval_card_request_ref", length = 100, unique = true)
    private String approvalCardRequestRef;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public void approve(String approvalId, String approvalCardRequestRef, Long amount, Instant approvedAt) {
        this.approvalId = approvalId;
        this.amount = amount;
        this.approvalCardRequestRef = approvalCardRequestRef;
        this.approvalStatus = CardApprovalStatus.SUCCESS;
        this.approvedAt = approvedAt;
    }
}
