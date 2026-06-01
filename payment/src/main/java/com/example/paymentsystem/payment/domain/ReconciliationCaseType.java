package com.example.paymentsystem.payment.domain;

public enum ReconciliationCaseType {
    MISSING_ON_CARD,
    MISSING_ON_PG,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    AGGREGATE
}