package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.client.card.CardConcurrencyLimiterRegistry;
import com.example.paymentsystem.payment.domain.CardCompany;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/concurrency-limits")
@RequiredArgsConstructor
public class ConcurrencyLimitController {

    private final CardConcurrencyLimiterRegistry registry;

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        Arrays.stream(CardCompany.values()).forEach(company ->
                result.put(company.name(), registry.getCurrentLimit(company))
        );
        return result;
    }
}
