package com.example.paymentsystem.card.dto;

public record CaptureResponse(
        boolean success,
        String externalId
) {
}
