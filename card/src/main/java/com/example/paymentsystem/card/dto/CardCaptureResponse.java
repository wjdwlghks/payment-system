package com.example.paymentsystem.card.dto;

public record CardCaptureResponse(
        boolean success,
        String externalId
) {
}
