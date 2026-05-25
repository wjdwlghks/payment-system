package com.example.paymentsystem.payment.dto;

public record RefundRequestContext(
        Long transactionId,
        String paymentKey,
        String refundKey,
        String captureId,
        Long amount
) {
}
