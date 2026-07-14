package com.example.paymentsystem.card.dto;

public record RefundRequest(
        String cardRequestRef,
        Long amount
) {
}