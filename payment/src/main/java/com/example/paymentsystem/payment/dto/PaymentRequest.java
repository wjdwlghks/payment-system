package com.example.paymentsystem.payment.dto;

public record PaymentRequest(
        String orderId,
        String merchantId,
        Long amount
) {
}
