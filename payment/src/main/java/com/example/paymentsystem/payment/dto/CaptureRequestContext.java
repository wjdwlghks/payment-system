package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.CardCompany;

public record CaptureRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String authorizationId,
        String paymentKey,
        String orderId,
        Long amount,
        String cardRequestRef,
        CardCompany cardCompany
) {
}
