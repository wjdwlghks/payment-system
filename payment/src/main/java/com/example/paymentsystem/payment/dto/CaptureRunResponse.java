package com.example.paymentsystem.payment.dto;

public record CaptureRunResponse(
        Long batchId,
        String batchCode,
        int attempted,
        int succeeded,
        int failed,
        int unknown,
        long succeededAmount
) {
}
