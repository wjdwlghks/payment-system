package com.example.paymentsystem.card.dto;

import java.time.Instant;

public record CaptureInquiryResponse(
        String status,
        String externalId,
        Instant capturedAt
) {
}
