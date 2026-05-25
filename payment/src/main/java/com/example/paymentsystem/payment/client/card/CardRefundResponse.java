package com.example.paymentsystem.payment.client.card;

import java.time.Instant;

public record CardRefundResponse(
        boolean success,
        String externalId,
        Instant refundedAt
) {
}
