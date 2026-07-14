package com.example.paymentsystem.payment.client.card;

public record CardRefundRequest(
        String cardRequestRef,
        Long amount
) {
}
