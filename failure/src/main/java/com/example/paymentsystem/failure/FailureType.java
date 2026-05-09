package com.example.paymentsystem.failure;

public enum FailureType {
    ERROR_500(0L),
    TIMEOUT_BEFORE_PROCESS(5_000L),
    TIMEOUT_AFTER_PROCESS(5_000L),
    SLOW_SUCCESS(2_000L),
    CONNECT_FAILURE(1_000L);

    private final long defaultDelayMs;

    FailureType(long defaultDelayMs) {
        this.defaultDelayMs = defaultDelayMs;
    }

    public long defaultDelayMs() {
        return defaultDelayMs;
    }
}
