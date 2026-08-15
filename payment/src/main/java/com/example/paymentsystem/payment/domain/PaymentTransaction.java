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

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "card_request_ref", nullable = false, length = 100, updatable = false)
    private String cardRequestRef;

    /**
     * 다음 inquiry가 허용되는 시각. UNKNOWN/REQUESTED 복구 조회의 <b>대상 조건이자 정렬 키</b>다.
     *
     * <p>시도할 때마다 백오프만큼 미래로 밀린다. 이 전진이 없으면 해소 불가능한 행이
     * 정렬 맨 앞을 영구 점유해, 같은 배치 크기 안에서 다른 카드사의 결제가 영원히 조회되지 않는다.
     */
    @Column(name = "next_inquiry_at")
    private Instant nextInquiryAt;

    /** 백오프 사다리의 몇 번째 칸인지. */
    @Column(name = "inquiry_attempts", nullable = false)
    private Integer inquiryAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentTransaction(
            PaymentIntent paymentIntent,
            TransactionType type,
            Long amount
    ) {
        this.paymentIntent = paymentIntent;
        this.type = type;
        this.amount = amount;
        this.status = TransactionStatus.REQUESTED;
        this.cardRequestRef = "pg-" + UUID.randomUUID();
        this.inquiryAttempts = 0;
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

    /**
     * UNKNOWN 진입 시 곧바로 조회 대상이 되도록 due 시각을 지금으로 찍는다.
     * 채워두지 않으면 {@code next_inquiry_at <= now} 조건이 NULL이 되어 영원히 조회되지 않는다.
     */
    public void markUnknown() {
        this.status = TransactionStatus.UNKNOWN;
        this.nextInquiryAt = Instant.now();
    }

    /**
     * 다음 조회 예약을 DB에 남긴다. 실제 예약은 인메모리 큐가 들고 있지만, 그 큐는 프로세스가
     * 죽으면 사라지므로 <b>여기 적힌 값이 유일한 durable 기록</b>이다. sweeper가 "큐가 놓쳤다"를
     * 판정하는 기준이자, 재기동 시 남은 지연을 그대로 복원하는 근거가 된다.
     */
    public void scheduleNextInquiry(Instant nextInquiryAt) {
        this.inquiryAttempts = this.inquiryAttempts + 1;
        this.nextInquiryAt = nextInquiryAt;
    }

    public boolean isPendingInquiry() {
        return this.status == TransactionStatus.REQUESTED || this.status == TransactionStatus.UNKNOWN;
    }
}
