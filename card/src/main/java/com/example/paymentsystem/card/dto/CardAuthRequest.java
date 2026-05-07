package com.example.paymentsystem.card.dto;

public record CardAuthRequest(
        String authIdempotentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}
