package com.example.paymentsystem.payment.client.card;

public record CardCaptureResponse(
        boolean success,
        String externalId
) {
}

