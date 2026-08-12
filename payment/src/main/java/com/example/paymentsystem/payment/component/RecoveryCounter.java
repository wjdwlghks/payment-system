package com.example.paymentsystem.payment.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class RecoveryCounter {

    private static final List<String> INQUIRY_TYPES =
            List.of("auth", "fds", "approve");

    private final Map<String, InquiryStats> inquiryStats = new LinkedHashMap<>();

    public RecoveryCounter() {
        INQUIRY_TYPES.forEach(type -> inquiryStats.put(type, new InquiryStats()));
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
        inquiryStats.values().forEach(InquiryStats::reset);
    }

    public Snapshot snapshot() {
        Map<String, InquirySnapshot> inquirySnap = new LinkedHashMap<>();
        inquiryStats.forEach((k, v) -> inquirySnap.put(k,
                new InquirySnapshot(v.total.get(), v.success.get(), v.failed.get(), v.notFound.get(), v.inProgress.get())));

        return new Snapshot(inquirySnap);
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

    public record InquirySnapshot(long total, long success, long failed, long notFound, long inProgress) {}
    public record Snapshot(Map<String, InquirySnapshot> inquiry) {}
}
