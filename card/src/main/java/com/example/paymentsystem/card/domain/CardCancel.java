package com.example.paymentsystem.card.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 승인취소(void) — 매입 전 승인건을 되돌린다.
 *
 * <p>매입({@link CardCapture})과 상호배타적이다. 한쪽이 존재하면 다른 쪽 요청은 거절된다 —
 * 그 판정은 PG를 믿지 않고 카드사가 직접 한다.
 */
@Entity
@Table(name = "card_cancel")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class CardCancel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_id", nullable = false, length = 100, unique = true)
    private String cancelId;

    @Column(name = "approval_id", nullable = false, length = 100, unique = true)
    private String approvalId;

    @Column(name = "card_request_ref", nullable = false, length = 100, unique = true)
    private String cardRequestRef;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CardCancelStatus status;

    @Column(name = "canceled_at", nullable = false)
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
