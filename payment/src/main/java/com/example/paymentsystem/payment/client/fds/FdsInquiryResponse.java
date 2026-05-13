package com.example.paymentsystem.payment.client.fds;

public record FdsInquiryResponse(
        String status,
        String result,
        String externalId
) {
}
