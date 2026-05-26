package com.example.paymentsystem.card.dto;

public record RefundRequest(
        String refundIdempotentKey,
        Long amount
) {
}