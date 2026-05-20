package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.ClearingBatch;
import com.example.paymentsystem.payment.domain.ClearingBatchStatus;
import java.time.Instant;

public record ClearingBatchResponse(
        boolean created,
        String batchCode,
        ClearingBatchStatus status,
        Instant windowStart,
        Instant windowEnd,
        Long totalAmount,
        Integer itemCount,
        String message
) {

    public static ClearingBatchResponse noTarget(Instant windowStart, Instant windowEnd) {
        return new ClearingBatchResponse(
                false,
                null,
                null,
                windowStart,
                windowEnd,
                0L,
                0,
                "No clearing target"
        );
    }

    public static ClearingBatchResponse created(ClearingBatch batch) {
        return new ClearingBatchResponse(
                true,
                batch.getBatchCode(),
                batch.getStatus(),
                batch.getWindowStart(),
                batch.getWindowEnd(),
                batch.getTotalAmount(),
                batch.getItemCount(),
                null
        );
    }
}
