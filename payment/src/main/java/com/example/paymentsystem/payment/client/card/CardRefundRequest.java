package com.example.paymentsystem.payment.client.card;

public record CardRefundRequest(
        String refundIdempotentKey,
        String cardRequestRef,
        Long amount
) {
}
