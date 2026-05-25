package com.example.paymentsystem.payment.exception;

import lombok.Getter;

@Getter
public class RefundValidationException extends RuntimeException {

    private final int statusCode;

    public RefundValidationException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
