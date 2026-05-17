package com.example.paymentsystem.payment.dto;

import java.time.Instant;

public record PaymentWebhookRequest(
        String eventId,
        String eventType,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount,
        String status,
        String failedStage,
        Instant occurredAt
) {
}
