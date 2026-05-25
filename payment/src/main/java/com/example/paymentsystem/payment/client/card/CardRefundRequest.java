package com.example.paymentsystem.payment.client.card;

public record CardRefundRequest(
        String refundIdempotentKey,
        Long amount
) {
}
