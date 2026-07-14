package com.example.paymentsystem.payment.dto;

public record AuthRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount,
        String cardRequestRef
) {
}

