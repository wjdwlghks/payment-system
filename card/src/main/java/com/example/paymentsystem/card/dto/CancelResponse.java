package com.example.paymentsystem.card.dto;

public record CancelResponse(
        boolean success,
        String externalId
) {
}
