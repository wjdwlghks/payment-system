package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.RefundStatus;

public record RefundResponse(
    String refundKey,
    RefundStatus status,
    Long amount
) {
}
