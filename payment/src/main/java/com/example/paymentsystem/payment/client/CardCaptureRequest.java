package com.example.paymentsystem.payment.client;

public record CardCaptureRequest(
        String captureIdempotentKey,
        String orderId,
        Long amount
) {
}

