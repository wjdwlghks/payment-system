package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.CardCompany;

public record RefundRequestContext(
        Long transactionId,
        String paymentKey,
        String refundKey,
        String captureId,
        Long amount,
        String cardRequestRef,
        CardCompany cardCompany
) {
}
