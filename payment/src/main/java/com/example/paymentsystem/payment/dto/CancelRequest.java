package com.example.paymentsystem.payment.dto;

public record CancelRequest(
        String cancelKey,
        Long amount
) {
}
