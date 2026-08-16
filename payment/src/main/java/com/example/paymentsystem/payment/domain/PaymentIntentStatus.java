package com.example.paymentsystem.payment.domain;

public enum PaymentIntentStatus {
    AUTH_REQUESTED,
    UNKNOWN_AUTH,
    AUTH_FAILED,
    AUTHENTICATED,
    FDS_REQUESTED,
    UNKNOWN_FDS,
    FDS_PASSED,
    FDS_FAILED,
    APPROVE_REQUESTED,
    UNKNOWN_APPROVE,
    APPROVE_FAILED,
    APPROVED,

    /**
     * 인증까지 끝났는데 가맹점이 승인을 요청하지 않은 채 유효기간이 지났다.
     *
     * <p>{@code X_FAILED}와 구분하는 이유는 실패한 적이 없기 때문이다 — 카드사는 이 결제를
     * 거절하지 않았고, 애초에 한도도 잡지 않았다(인증은 금액을 다루지 않는다). 이걸
     * {@code AUTH_FAILED}로 뭉개면 웹훅 {@code failed}를 받는 가맹점이 "카드사가 거절함"과
     * "사용자가 결제창을 닫음"을 구분할 수 없게 된다.
     */
    EXPIRED,

    /**
     * 승인된 결제를 매입 전에 되돌렸다(카드사 승인취소).
     *
     * <p>매입 이후에는 이 상태로 올 수 없다 — 그때부터는 환불의 영역이다.
     * 원장은 매입 시점에만 기표되므로 이 상태에는 되돌릴 회계 기록이 없다.
     */
    CANCELED
}
