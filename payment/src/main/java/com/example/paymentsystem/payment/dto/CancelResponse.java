package com.example.paymentsystem.payment.dto;

public record CancelResponse(
        String paymentKey,
        String cancelType,
        String status,
        Long amount
) {
    public static CancelResponse authCancelled(String paymentKey) {
        return new CancelResponse(paymentKey, "AUTH_CANCEL", "CANCELLED", null);
    }

    public static CancelResponse captureCancelled(String paymentKey, Long amount) {
        return new CancelResponse(paymentKey, "CAPTURE_CANCEL", "SUCCEEDED", amount);
    }
}
