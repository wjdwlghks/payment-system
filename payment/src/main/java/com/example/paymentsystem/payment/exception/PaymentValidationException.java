package com.example.paymentsystem.payment.exception;

import lombok.Getter;

@Getter
public class PaymentValidationException extends RuntimeException {

    private final int statusCode;

    public PaymentValidationException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
