package com.example.paymentsystem.fds.dto;

public record FraudCheckInquiryResponse(
        String status,
        String result,
        String externalId
) {
}
