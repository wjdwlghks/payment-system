package com.example.paymentsystem.payment.domain;

public enum PaymentIntentStatus {
    AUTH_REQUESTED,
    UNKNOWN_AUTH,
    AUTH_FAILED,
    AUTH_READY,
    FDS_REQUESTED,
    UNKNOWN_FDS,
    FDS_FAILED,
    CAPTURE_REQUESTED,
    UNKNOWN_CAPTURE,
    CAPTURE_FAILED,
    DONE,
    CANCELED
}
