package com.example.paymentsystem.card.dto;

import java.time.Instant;

public record ApproveInquiryResponse(
        String status,
        String externalId,
        Instant approvedAt
) {
}
