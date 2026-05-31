package com.example.paymentsystem.payment.dto;

import java.time.LocalDate;

public record IngestReconciliationRequest(
        String filePath,
        String cardCompany,
        LocalDate businessDate
) {
}
