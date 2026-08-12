package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.CardCompany;

public record CaptureRequestContext(
        Long transactionId,
        String approvalId,
        Long amount,
        String cardRequestRef,
        CardCompany cardCompany
) {
}
