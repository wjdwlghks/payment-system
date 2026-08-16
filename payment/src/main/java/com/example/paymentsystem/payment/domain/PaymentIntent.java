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

    @Column(name = "authenticated_at")
    private Instant authenticatedAt;

    @Column(name = "approval_id", length = 100)
    private String approvalId;

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

    public void markAuthenticated(Instant authenticatedAt) {
        this.status = PaymentIntentStatus.AUTHENTICATED;
        this.authenticatedAt = authenticatedAt;
    }

    public void markAuthFailed() {
        this.status = PaymentIntentStatus.AUTH_FAILED;
    }

    public void markFdsRequested() {
        if (this.status != PaymentIntentStatus.AUTHENTICATED) {
            throw new IllegalStateException("Cannot start FDS: intent status is " + this.status);
        }
        this.status = PaymentIntentStatus.FDS_REQUESTED;
    }

    public void markFdsFailed() {
        this.status = PaymentIntentStatus.FDS_FAILED;
    }

    public void markFdsPassed() {
        this.status = PaymentIntentStatus.FDS_PASSED;
    }

    /** 승인 선행조건. 도메인 불변식과 API 검증이 같은 판정을 쓰도록 여기 한 곳에 둔다. */
    public boolean isApprovable() {
        return this.status == PaymentIntentStatus.FDS_PASSED;
    }

    public void markApproveRequested() {
        if (!isApprovable()) {
            throw new IllegalStateException("Cannot start approve: intent status is " + this.status);
        }
        this.status = PaymentIntentStatus.APPROVE_REQUESTED;
    }

    /** 매입 선행조건 — 승인이 확정된 결제. 이미 매입됐는지는 멱등키가 가려낸다. */
    public boolean isCapturable() {
        return this.status == PaymentIntentStatus.APPROVED;
    }

    public void markApproveFailed() {
        this.status = PaymentIntentStatus.APPROVE_FAILED;
    }

    public void markApproved(String approvalId) {
        this.status = PaymentIntentStatus.APPROVED;
        this.approvalId = approvalId;
    }

    public void markAuthUnknown() {
        this.status = PaymentIntentStatus.UNKNOWN_AUTH;
    }

    public void markFdsUnknown() {
        this.status = PaymentIntentStatus.UNKNOWN_FDS;
    }

    public void markApproveUnknown() {
        this.status = PaymentIntentStatus.UNKNOWN_APPROVE;
    }

    /**
     * 만료 선행조건 — 인증·FDS까지 끝났는데 승인 요청이 오지 않은 상태.
     *
     * <p>{@code FDS_PASSED}만 대상이다. {@code APPROVE_REQUESTED} 이후는 카드사에 요청이
     * 나갔다는 뜻이라 만료가 아니라 조회(inquiry)가 결론을 내야 한다.
     */
    public boolean isExpirable() {
        return this.status == PaymentIntentStatus.FDS_PASSED;
    }

    public void markExpired() {
        if (!isExpirable()) {
            throw new IllegalStateException("Cannot expire: intent status is " + this.status);
        }
        this.status = PaymentIntentStatus.EXPIRED;
    }

    /**
     * 취소 선행조건 — 승인이 확정된 결제. <b>매입 여부는 여기서 판정하지 않는다.</b>
     *
     * <p>매입은 {@code PaymentIntent.status}를 바꾸지 않기 때문이다(설계상 CAPTURE
     * 트랜잭션으로만 추적한다). 그래서 "이미 매입됐는가"는 CAPTURE tx를 직접 봐야 하고,
     * 그 판정은 {@code CancelService}가 한다.
     */
    public boolean isCancelable() {
        return this.status == PaymentIntentStatus.APPROVED;
    }

    public void markCanceled() {
        if (!isCancelable()) {
            throw new IllegalStateException("Cannot cancel: intent status is " + this.status);
        }
        this.status = PaymentIntentStatus.CANCELED;
    }
}
