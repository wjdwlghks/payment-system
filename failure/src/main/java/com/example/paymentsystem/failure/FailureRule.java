package com.example.paymentsystem.failure;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
@Getter
public class FailureRule {

    private final FailureType failure;
    private final long delayMs;
    private final AtomicInteger remaining;

    public FailureRule(FailureType failure, Long delayMs, int remaining) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        if (remaining < 1) {
            throw new IllegalArgumentException("Remaining should be greater than 0");
        }
        this.failure = failure;
        this.delayMs = (delayMs != null) ? delayMs : failure.defaultDelayMs();
        this.remaining = new AtomicInteger(remaining);
    }

    boolean decrementAndIsExhausted() {
        return remaining.decrementAndGet() <= 0;
    }

    public int remainingSnapshot() {
        return remaining.get();
    }

}
