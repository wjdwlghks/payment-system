package com.example.paymentsystem.payment.domain;

/**
 * 멱등키 포맷 단일 정의.
 * 동기 흐름(PaymentService)과 복구 경로(InquiryService/FdsExecutionService)가
 * 같은 키를 재구성해야 하므로 포맷을 한 곳에 모은다.
 */
public final class IdempotentKeys {

    private IdempotentKeys() {
    }

    public static String paymentRequest(String merchantId, String orderId) {
        return merchantId + ":" + orderId;
    }

    public static String paymentApprove(String merchantId, String paymentKey) {
        return perPayment(merchantId, paymentKey);
    }

    public static String paymentCapture(String merchantId, String paymentKey) {
        return perPayment(merchantId, paymentKey);
    }

    // 승인·매입은 결제 건당 1회라 키 포맷이 같다.
    // 서로 다른 키로 취급되는 건 idempotency_keys의 (operation, idempotent_key) UNIQUE 덕이다.
    private static String perPayment(String merchantId, String paymentKey) {
        return merchantId + ":" + paymentKey;
    }
}
