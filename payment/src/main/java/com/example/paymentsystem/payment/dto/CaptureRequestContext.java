package com.example.paymentsystem.payment.dto;

public record CaptureRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String authorizationId,
        String paymentKey,
        String orderId,
        Long amount
) {
}

