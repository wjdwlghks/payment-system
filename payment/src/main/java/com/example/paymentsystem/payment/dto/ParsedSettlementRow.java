package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.SettlementStatus;
import com.example.paymentsystem.payment.domain.SettlementType;
import java.time.Instant;

public record ParsedSettlementRow(
        String approvalNo,
        long amount,
        Instant transactedAt,
        SettlementType txType,
        SettlementStatus txStatus,
        String originalApprovalNo
) {
}
