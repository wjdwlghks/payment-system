package com.example.paymentsystem.payment.dto;

public record FdsRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount
) {
}

