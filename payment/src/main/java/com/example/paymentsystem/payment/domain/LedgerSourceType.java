package com.example.paymentsystem.payment.domain;

public enum LedgerSourceType {
    PAYMENT_TRANSACTION,
    REFUND_TRANSACTION,
    CLEARING_REQUEST,
    SETTLEMENT_REQUEST,
    PAYOUT_REQUEST
}
