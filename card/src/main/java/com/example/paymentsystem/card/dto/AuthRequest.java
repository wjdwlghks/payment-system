package com.example.paymentsystem.card.dto;

public record AuthRequest(
        String authIdempotentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}
