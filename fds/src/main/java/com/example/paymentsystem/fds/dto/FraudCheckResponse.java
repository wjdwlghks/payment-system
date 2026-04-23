package com.example.paymentsystem.fds.dto;

public record FraudCheckResponse(
        boolean success,
        String result,
        String externalId
) {
}
