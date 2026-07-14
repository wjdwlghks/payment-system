package com.example.paymentsystem.card.dto;

public record AuthRequest(
        String cardRequestRef,
        String orderId,
        String merchantId,
        Long amount
) {
}
