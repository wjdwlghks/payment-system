package com.example.paymentsystem.payment.client.card;

public record CardAuthRequest(
        String authIdempotentKey,
        String cardRequestRef,
        String orderId,
        String merchantId,
        Long amount
) {
}

