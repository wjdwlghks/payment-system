package com.example.paymentsystem.payment.domain;

public enum ReconBatchStatus {
    INGESTING,
    INGESTED,
    INGESTED_PARTIAL,
    MATCHING,
    COMPLETED,
    ABORTED
}
