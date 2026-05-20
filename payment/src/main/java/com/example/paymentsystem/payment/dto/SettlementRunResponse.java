package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.SettlementRun;
import com.example.paymentsystem.payment.domain.SettlementRunStatus;
import java.time.Instant;

public record SettlementRunResponse(
        boolean created,
        String runCode,
        SettlementRunStatus status,
        Instant windowStart,
        Instant windowEnd,
        Long totalAmount,
        Integer itemCount,
        String message
) {

    public static SettlementRunResponse noTarget(Instant windowStart, Instant windowEnd) {
        return new SettlementRunResponse(
                false,
                null,
                null,
                windowStart,
                windowEnd,
                0L,
                0,
                "No settlement target"
        );
    }

    public static SettlementRunResponse created(SettlementRun run) {
        return new SettlementRunResponse(
                true,
                run.getRunCode(),
                run.getStatus(),
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getTotalAmount(),
                run.getItemCount(),
                null
        );
    }
}
