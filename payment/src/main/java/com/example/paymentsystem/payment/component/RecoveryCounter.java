package com.example.paymentsystem.payment.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class RecoveryCounter {

    private static final List<String> RETRY_NAMES =
            List.of("cardAuth", "cardCapture", "cardRefund", "fdsCheck");
    private static final List<String> INQUIRY_TYPES =
            List.of("auth", "fds", "capture", "refund");

    private final Map<String, RetryStats> retryStats = new LinkedHashMap<>();
    private final Map<String, InquiryStats> inquiryStats = new LinkedHashMap<>();

    public RecoveryCounter() {
        RETRY_NAMES.forEach(name -> retryStats.put(name, new RetryStats()));
        INQUIRY_TYPES.forEach(type -> inquiryStats.put(type, new InquiryStats()));
    }

    public void incrementRetryAttempt(String name) {
        retryStats.get(name).attempts.incrementAndGet();
    }

    public void incrementRetrySucceeded(String name) {
        retryStats.get(name).succeededAfterRetry.incrementAndGet();
    }

    public void incrementRetryFailed(String name) {
        retryStats.get(name).failedAfterRetry.incrementAndGet();
    }

    public void incrementInquiryTotal(String type) {
        inquiryStats.get(type).total.incrementAndGet();
    }

    public void incrementInquiryResult(String type, String result) {
        InquiryStats stats = inquiryStats.get(type);
        switch (result) {
            case "success"     -> stats.success.incrementAndGet();
            case "failed"      -> stats.failed.incrementAndGet();
            case "not_found"   -> stats.notFound.incrementAndGet();
            case "in_progress" -> stats.inProgress.incrementAndGet();
        }
    }

    public void reset() {
        retryStats.values().forEach(RetryStats::reset);
        inquiryStats.values().forEach(InquiryStats::reset);
    }

    public Snapshot snapshot() {
        Map<String, RetrySnapshot> retrySnap = new LinkedHashMap<>();
        retryStats.forEach((k, v) -> retrySnap.put(k,
                new RetrySnapshot(v.attempts.get(), v.succeededAfterRetry.get(), v.failedAfterRetry.get())));

        Map<String, InquirySnapshot> inquirySnap = new LinkedHashMap<>();
        inquiryStats.forEach((k, v) -> inquirySnap.put(k,
                new InquirySnapshot(v.total.get(), v.success.get(), v.failed.get(), v.notFound.get(), v.inProgress.get())));

        return new Snapshot(retrySnap, inquirySnap);
    }

    public static class RetryStats {
        final AtomicLong attempts = new AtomicLong();
        final AtomicLong succeededAfterRetry = new AtomicLong();
        final AtomicLong failedAfterRetry = new AtomicLong();

        void reset() {
            attempts.set(0);
            succeededAfterRetry.set(0);
            failedAfterRetry.set(0);
        }
    }

    public static class InquiryStats {
        final AtomicLong total = new AtomicLong();
        final AtomicLong success = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong notFound = new AtomicLong();
        final AtomicLong inProgress = new AtomicLong();

        void reset() {
            total.set(0);
            success.set(0);
            failed.set(0);
            notFound.set(0);
            inProgress.set(0);
        }
    }

    public record RetrySnapshot(long attempts, long succeededAfterRetry, long failedAfterRetry) {}
    public record InquirySnapshot(long total, long success, long failed, long notFound, long inProgress) {}
    public record Snapshot(Map<String, RetrySnapshot> retry, Map<String, InquirySnapshot> inquiry) {}
}
