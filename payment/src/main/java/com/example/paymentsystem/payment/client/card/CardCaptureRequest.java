package com.example.paymentsystem.payment.client.card;

public record CardCaptureRequest(
        String cardRequestRef,
        String orderId,
        Long amount
) {
}

