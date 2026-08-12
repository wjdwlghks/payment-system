package com.example.paymentsystem.payment.client.card;

public record CardApproveRequest(
        String cardRequestRef,
        String orderId,
        Long amount
) {
}

