package com.example.paymentsystem.payment.exception;

public class ReconciliationCarryOverException extends RuntimeException {

    private final long previousBalance;

    public ReconciliationCarryOverException(long previousBalance) {
        super("carry-over balance detected from prior batches: " + previousBalance
                + " — manual intervention required before this batch can be reconciled");
        this.previousBalance = previousBalance;
    }

    public long getPreviousBalance() {
        return previousBalance;
    }
}
