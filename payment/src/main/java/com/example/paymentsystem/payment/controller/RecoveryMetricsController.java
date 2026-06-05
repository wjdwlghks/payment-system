package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.component.RecoveryCounter;
import com.example.paymentsystem.payment.component.RecoveryCounter.InquirySnapshot;
import com.example.paymentsystem.payment.component.RecoveryCounter.RetrySnapshot;
import com.example.paymentsystem.payment.repository.ReconBatchRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/metrics/recovery")
@RequiredArgsConstructor
public class RecoveryMetricsController {

    private final RecoveryCounter recoveryCounter;
    private final ReconBatchRepository reconBatchRepository;

    @GetMapping
    public RecoveryMetricsResponse get() {
        RecoveryCounter.Snapshot snapshot = recoveryCounter.snapshot();
        long autoResolved = reconBatchRepository.sumAutoResolvedCount();
        return new RecoveryMetricsResponse(snapshot.retry(), snapshot.inquiry(), autoResolved);
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        recoveryCounter.reset();
        return ResponseEntity.noContent().build();
    }

    public record RecoveryMetricsResponse(
            Map<String, RetrySnapshot> retry,
            Map<String, InquirySnapshot> inquiry,
            long reconAutoResolved
    ) {}
}
