package com.example.paymentsystem.card.dto;

public record CardAuthRequest(
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}
