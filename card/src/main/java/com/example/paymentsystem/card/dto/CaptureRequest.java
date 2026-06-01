package com.example.paymentsystem.card.dto;

public record CaptureRequest(
        String captureIdempotentKey,
        String cardRequestRef,
        String orderId,
        Long amount
) {
}
