package com.example.paymentsystem.payment.client;

public record CardCaptureRequest(
        String paymentKey,
        String orderId,
        Long amount
) {
}

