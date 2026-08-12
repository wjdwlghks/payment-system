package com.example.paymentsystem.payment.client.card;

public record CardCaptureRequest(
        String cardRequestRef,
        Long amount
) {
}
