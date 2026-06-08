package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.domain.CardCompany;

public class ConcurrencyLimitExceededException extends RuntimeException {

    public ConcurrencyLimitExceededException(CardCompany company) {
        super("Concurrency limit exceeded for: " + company);
    }
}
