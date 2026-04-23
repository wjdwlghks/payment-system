package com.example.paymentsystem.card.dto;

public record CardCaptureRequest(
        String paymentKey,
        String orderId,
        Long amount
) {
}
