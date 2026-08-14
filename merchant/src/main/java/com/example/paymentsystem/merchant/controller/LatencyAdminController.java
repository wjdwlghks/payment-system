package com.example.paymentsystem.merchant.controller;

import com.example.paymentsystem.merchant.component.LatencyRecorder;
import com.example.paymentsystem.merchant.component.PaymentFlow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가맹점 관점 지연 지표.
 *
 * <p>읽을 때 순서가 있다. {@code inFlight}가 0이 아니면 아직 안 끝난 결제가 있다는 뜻이고,
 * 그것들은 <b>가장 느린 건들</b>이므로 분포가 낙관 편향돼 있다. {@code workerQueueDepth}가
 * 쌓여 있으면 merchant 자신이 병목이라 지연값에 merchant 대기가 섞였다는 뜻이다.
 * 둘 다 깨끗할 때만 {@code userLatency}를 믿을 수 있다.
 */
@RestController
@RequestMapping("/admin/latency")
public class LatencyAdminController {

    private final LatencyRecorder recorder;
    private final PaymentFlow paymentFlow;

    public LatencyAdminController(LatencyRecorder recorder, PaymentFlow paymentFlow) {
        this.recorder = recorder;
        this.paymentFlow = paymentFlow;
    }

    @GetMapping
    public LatencyRecorder.Snapshot get() {
        return recorder.snapshot(paymentFlow.queueDepth(), paymentFlow.activeWorkers());
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        recorder.reset();
        paymentFlow.reset();
        return ResponseEntity.noContent().build();
    }
}
