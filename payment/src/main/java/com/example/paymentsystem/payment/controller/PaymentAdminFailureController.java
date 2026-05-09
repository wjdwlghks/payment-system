package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.failure.FailureRegistry;
import com.example.paymentsystem.failure.FailureRule;
import com.example.paymentsystem.failure.FailureType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/failure")
@RequiredArgsConstructor
public class PaymentAdminFailureController {

    private final FailureRegistry registry;

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody RegisterRequest body) {
        FailureRule rule = new FailureRule(
                body.failure(),
                body.delayMs(),
                body.remaining() != null ? body.remaining() : 1
        );
        registry.register(body.endpoint(), rule);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Map<String, RuleView> list() {
        return registry.snapshot().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new RuleView(
                                e.getValue().getFailure(),
                                e.getValue().getDelayMs(),
                                e.getValue().remainingSnapshot()
                        )
                ));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        registry.clear();
        return ResponseEntity.noContent().build();
    }

    public record RegisterRequest(
            String endpoint,
            FailureType failure,
            Long delayMs,
            Integer remaining
    ) {
    }

    public record RuleView(
            FailureType failure,
            long delayMs,
            int remaining
    ) {
    }
}
