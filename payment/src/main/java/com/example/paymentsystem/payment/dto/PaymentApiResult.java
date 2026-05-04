package com.example.paymentsystem.payment.dto;

public record PaymentApiResult(
        int statusCode,
        String body
) {
}
