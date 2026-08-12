package com.example.paymentsystem.payment.domain;

public enum IdempotencyOperation {
    PAYMENT_REQUEST,
    PAYMENT_APPROVE,
    PAYMENT_CAPTURE
}
