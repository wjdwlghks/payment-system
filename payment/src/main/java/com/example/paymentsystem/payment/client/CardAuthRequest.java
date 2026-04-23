package com.example.paymentsystem.payment.client;

public record CardAuthRequest(
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}

