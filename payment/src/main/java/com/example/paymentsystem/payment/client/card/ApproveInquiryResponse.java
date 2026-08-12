package com.example.paymentsystem.payment.client.card;

import java.time.Instant;

public record ApproveInquiryResponse(
        String status,
        String externalId,
        Instant capturedAt
) {
}
