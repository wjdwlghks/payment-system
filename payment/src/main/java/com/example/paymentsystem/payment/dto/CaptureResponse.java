package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.TransactionStatus;

/**
 * 매입 응답. PaymentIntent 상태는 매입 전후로 APPROVED에 머물기 때문에,
 * 가맹점이 매입 결과를 알려면 CAPTURE 트랜잭션 상태를 따로 내려줘야 한다.
 */
public record CaptureResponse(
        String paymentKey,
        String orderId,
        Long amount,
        TransactionStatus captureStatus
) {
}
