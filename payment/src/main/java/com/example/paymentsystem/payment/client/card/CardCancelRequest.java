package com.example.paymentsystem.payment.client.card;

public record CardCancelRequest(
        String cardRequestRef,
        Long amount
) {
}
