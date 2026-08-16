package com.example.paymentsystem.payment.client.card;

public record CardCancelResponse(
        boolean success,
        String externalId
) {
}
