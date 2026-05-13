package com.example.paymentsystem.card.dto;

import java.time.Instant;

public record AuthInquiryResponse(
        String status,
        String externalId,
        Instant authorizedAt
) {
}
