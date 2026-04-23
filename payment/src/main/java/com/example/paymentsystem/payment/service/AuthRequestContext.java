package com.example.paymentsystem.payment.service;

public record AuthRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}

