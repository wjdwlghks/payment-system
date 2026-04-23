package com.example.paymentsystem.payment.client;

import java.time.Instant;

public record CardAuthResponse(
        boolean success,
        String externalId,
        Instant authorizedAt
) {
}
