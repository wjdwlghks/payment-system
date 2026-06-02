package com.example.paymentsystem.payment.domain;

public enum LedgerSourceType {
    PAYMENT_TRANSACTION,
    REFUND_TRANSACTION,
    CLEARING_BATCH,
    SETTLEMENT_RUN,
    PAYOUT_REQUEST,
    RECON_ADJUSTMENT
}
