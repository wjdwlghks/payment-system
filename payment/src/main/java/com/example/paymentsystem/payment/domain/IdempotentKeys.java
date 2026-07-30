package com.example.paymentsystem.payment.domain;

/**
 * 멱등키 포맷 단일 정의.
 * 동기 흐름(PaymentService/RefundService)과 복구 경로(InquiryService/FdsExecutionService)가
 * 같은 키를 재구성해야 하므로 포맷을 한 곳에 모은다.
 */
public final class IdempotentKeys {

    public static final String REFUND_SEPARATOR = ":refund:";

    private IdempotentKeys() {
    }

    public static String paymentRequest(String merchantId, String orderId) {
        return merchantId + ":" + orderId;
    }

    public static String paymentConfirm(String merchantId, String paymentKey) {
        return merchantId + ":" + paymentKey;
    }

    public static String paymentRefund(String paymentKey, String refundKey) {
        return paymentKey + REFUND_SEPARATOR + refundKey;
    }
}
