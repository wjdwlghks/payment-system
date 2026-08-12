package com.example.paymentsystem.payment.client.card;

import java.time.Instant;

public record CardAuthResponse(
        boolean success,
        String externalId,
        Instant authenticatedAt
) {
}
