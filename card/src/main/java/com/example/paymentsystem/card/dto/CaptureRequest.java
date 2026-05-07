package com.example.paymentsystem.card.dto;

public record CaptureRequest(
        String captureIdempotentKey,
        String orderId,
        Long amount
) {
}
