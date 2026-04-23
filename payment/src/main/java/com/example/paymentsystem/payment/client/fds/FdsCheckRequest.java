package com.example.paymentsystem.payment.client.fds;

public record FdsCheckRequest(
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}

