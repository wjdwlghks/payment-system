package com.example.paymentsystem.payment.domain;

public enum PaymentIntentStatus {
    AUTH_REQUESTED,
    UNKNOWN_AUTH,
    AUTH_FAILED,
    AUTH_READY,
    FDS_REQUESTED,
    UNKNOWN_FDS,
    FDS_READY,
    FDS_FAILED,
    APPROVE_REQUESTED,
    UNKNOWN_APPROVE,
    APPROVE_FAILED,
    DONE
}
