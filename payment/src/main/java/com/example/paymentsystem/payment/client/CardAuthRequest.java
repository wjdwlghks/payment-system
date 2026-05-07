package com.example.paymentsystem.payment.client;

public record CardAuthRequest(
        String authIdempotentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}

