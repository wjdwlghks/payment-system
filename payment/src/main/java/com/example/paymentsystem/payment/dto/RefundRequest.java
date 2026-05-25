package com.example.paymentsystem.payment.dto;

public record RefundRequest(
        String paymentKey,
        String refundKey,
        Long amount
) {
}
