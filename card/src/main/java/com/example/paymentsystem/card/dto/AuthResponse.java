package com.example.paymentsystem.card.dto;

import java.time.Instant;

public record AuthResponse(
        boolean success,
        String externalId,
        Instant authorizedAt
) {
}
