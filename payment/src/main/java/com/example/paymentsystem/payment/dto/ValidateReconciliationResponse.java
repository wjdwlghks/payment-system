package com.example.paymentsystem.payment.dto;

public record ValidateReconciliationResponse(
        Long reconBatchId,
        String batchStatus,
        int autoResolvedCount,
        int missingOnCardCount,
        int missingOnPgCount,
        int amountMismatchCount,
        int statusMismatchCount,
        int aggregateCount,
        Long clearingBatchId,
        String clearingBatchCode
) {
}
