package com.example.paymentsystem.card.dto;

import java.time.Instant;

public record CardAuthResponse(
        boolean success,
        String externalId,
        Instant authorizedAt
) {
}
