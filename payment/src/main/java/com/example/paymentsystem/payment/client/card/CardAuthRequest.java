package com.example.paymentsystem.payment.client.card;

public record CardAuthRequest(
        String cardRequestRef,
        String orderId,
        String merchantId
) {
}

