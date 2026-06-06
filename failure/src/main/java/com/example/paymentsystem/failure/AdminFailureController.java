package com.example.paymentsystem.failure;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/failure")
@RequiredArgsConstructor
public class AdminFailureController {

    private final FailureRegistry registry;

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody RegisterRequest body) {
        FailureRule rule = new FailureRule(
                body.failure(),
                body.delayMs(),
                body.remaining() != null ? body.remaining() : 1,
                body.triggerProbability()
        );
        registry.register(body.endpoint(), rule);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Map<String, List<RuleView>> list() {
        return registry.snapshot().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(r -> new RuleView(
                                        r.getFailure(),
                                        r.getDelayMs(),
                                        r.remainingSnapshot(),
                                        r.getTriggerProbability()
                                ))
                                .collect(Collectors.toList())
                ));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        registry.clear();
        return ResponseEntity.noContent().build();
    }

    public record RegisterRequest(
            String endpoint,        // alias: "auth", "capture", "fds_check"
            FailureType failure,
            Long delayMs,           // nullable → enum 기본값
            Integer remaining,      // nullable → 1
            Double triggerProbability  // nullable → 1.0 (always trigger)
    ) {}

    public record RuleView(
            FailureType failure,
            long delayMs,
            int remaining,
            double triggerProbability
    ) {}
}
