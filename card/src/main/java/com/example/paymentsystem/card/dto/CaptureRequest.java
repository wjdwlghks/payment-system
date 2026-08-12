package com.example.paymentsystem.card.dto;

public record CaptureRequest(
        String cardRequestRef,
        Long amount
) {
}
