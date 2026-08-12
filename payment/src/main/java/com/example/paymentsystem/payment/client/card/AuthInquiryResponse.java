package com.example.paymentsystem.payment.client.card;

import java.time.Instant;

public record AuthInquiryResponse(
        String status,
        String externalId,
        Instant authenticatedAt
) {
}
