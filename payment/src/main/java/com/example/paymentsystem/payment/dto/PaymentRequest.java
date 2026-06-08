package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.CardCompany;

public record PaymentRequest(
        String orderId,
        String merchantId,
        Long amount,
        CardCompany cardCompany
) {
}
