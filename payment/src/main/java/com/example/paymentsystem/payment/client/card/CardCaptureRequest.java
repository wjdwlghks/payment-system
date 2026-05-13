package com.example.paymentsystem.payment.client.card;

public record CardCaptureRequest(
        String captureIdempotentKey,
        String orderId,
        Long amount
) {
}

