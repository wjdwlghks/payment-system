package com.example.paymentsystem.payment.dto;

public record IngestReconciliationResponse(
        Long reconBatchId,
        String cardCompany,
        String businessDate,
        int rowCount,
        long fileTotalAmount,
        int ingestionFailedCount,
        String status
) {
}
