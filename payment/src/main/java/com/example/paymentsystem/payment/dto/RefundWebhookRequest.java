package com.example.paymentsystem.payment.dto;

import java.time.Instant;

public record RefundWebhookRequest(
        String eventId,
        String eventType,
        String refundKey,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount,
        String status,
        Instant occurredAt
) {
}
