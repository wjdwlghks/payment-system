package com.example.paymentsystem.fds.dto;

public record FraudCheckRequest(
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}
