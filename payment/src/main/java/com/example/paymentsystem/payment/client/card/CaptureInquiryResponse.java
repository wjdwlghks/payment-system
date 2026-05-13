package com.example.paymentsystem.payment.client.card;

import java.time.Instant;

public record CaptureInquiryResponse(
        String status,
        String externalId,
        Instant capturedAt
) {
}
