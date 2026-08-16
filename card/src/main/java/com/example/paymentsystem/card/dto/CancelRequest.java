package com.example.paymentsystem.card.dto;

public record CancelRequest(
        String cardRequestRef,
        Long amount
) {
}
