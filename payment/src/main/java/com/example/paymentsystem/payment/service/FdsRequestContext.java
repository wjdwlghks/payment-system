package com.example.paymentsystem.payment.service;

public record FdsRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}

