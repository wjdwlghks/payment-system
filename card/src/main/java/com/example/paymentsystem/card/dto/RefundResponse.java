package com.example.paymentsystem.card.dto;

import java.time.Instant;

public record RefundResponse(
        boolean success,
        String externalId,
        Instant refundedAt
) {
}