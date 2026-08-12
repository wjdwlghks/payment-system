package com.example.paymentsystem.card.dto;

public record ApproveRequest(
        String cardRequestRef,
        String orderId,
        Long amount
) {
}
