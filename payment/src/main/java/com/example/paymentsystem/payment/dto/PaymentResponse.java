package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import java.time.Instant;

public record PaymentResponse(
        String paymentKey,
        String orderId,
        PaymentIntentStatus status,
        Long amount,
        Instant authorizedAt
) {
}
