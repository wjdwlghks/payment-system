package com.example.paymentsystem.payment.client;

public record CardCaptureResponse(
        boolean success,
        String externalId
) {
}

