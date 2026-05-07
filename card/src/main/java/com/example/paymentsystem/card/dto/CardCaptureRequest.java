package com.example.paymentsystem.card.dto;

public record CardCaptureRequest(
        String captureIdempotentKey,
        String orderId,
        Long amount
) {
}
