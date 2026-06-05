package com.example.paymentsystem.failure;


import lombok.Getter;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public class FailureRule {

    private final FailureType failure;
    private final long delayMs;
    private final AtomicInteger remaining;
    private final double triggerProbability;

    public FailureRule(FailureType failure, Long delayMs, int remaining) {
        this(failure, delayMs, remaining, null);
    }

    public FailureRule(FailureType failure, Long delayMs, int remaining, Double triggerProbability) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        if (remaining < 1) {
            throw new IllegalArgumentException("Remaining should be greater than 0");
        }
        if (triggerProbability != null && (triggerProbability <= 0.0 || triggerProbability > 1.0)) {
            throw new IllegalArgumentException("triggerProbability must be in range (0, 1]");
        }
        this.failure = failure;
        this.delayMs = (delayMs != null) ? delayMs : failure.defaultDelayMs();
        this.remaining = new AtomicInteger(remaining);
        this.triggerProbability = (triggerProbability != null) ? triggerProbability : 1.0;
    }

    public boolean shouldTrigger() {
        return triggerProbability >= 1.0 || ThreadLocalRandom.current().nextDouble() < triggerProbability;
    }

    boolean decrementAndIsExhausted() {
        return remaining.decrementAndGet() <= 0;
    }

    public int remainingSnapshot() {
        return remaining.get();
    }

}
