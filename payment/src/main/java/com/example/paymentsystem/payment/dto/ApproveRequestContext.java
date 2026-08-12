package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.CardCompany;

public record ApproveRequestContext(
        Long paymentIntentId,
        Long transactionId,
        String authenticationId,
        String paymentKey,
        String orderId,
        String merchantId,
        Long amount,
        String cardRequestRef,
        CardCompany cardCompany
) {
}
