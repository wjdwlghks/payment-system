package com.example.paymentsystem.card.dto;

public record ApproveResponse(
        boolean success,
        String externalId
) {
}
