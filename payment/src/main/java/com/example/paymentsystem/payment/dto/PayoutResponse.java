package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.Payout;
import com.example.paymentsystem.payment.domain.PayoutStatus;
import java.time.Instant;

public record PayoutResponse(
        boolean created,
        String payoutCode,
        String merchantId,
        PayoutStatus status,
        Long amount,
        Instant requestedAt,
        Instant paidAt,
        String message
) {

    public static PayoutResponse noTarget(String merchantId) {
        return new PayoutResponse(
                false,
                null,
                merchantId,
                null,
                0L,
                null,
                null,
                "No payout target"
        );
    }

    public static PayoutResponse created(Payout payout) {
        return new PayoutResponse(
                true,
                payout.getPayoutCode(),
                payout.getMerchantId(),
                payout.getStatus(),
                payout.getAmount(),
                payout.getRequestedAt(),
                payout.getPaidAt(),
                null
        );
    }
}
