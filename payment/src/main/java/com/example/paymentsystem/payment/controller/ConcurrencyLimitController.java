package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.client.card.CardConcurrencyLimiterRegistry;
import com.example.paymentsystem.payment.domain.CardCompany;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 카드사별 현재 적응형 한도와 <b>누적 사전거절 수</b>.
     *
     * <p>사전거절은 요청이 카드사에 나가지도 못한 채 로컬에서 확정 실패가 된 건이다.
     * 결과(AUTH_FAILED)만 보면 카드사 거절과 구분되지 않으므로, 실패율이 높을 때
     * 원인이 카드사인지 우리 리미터인지 가르려면 이 숫자가 필요하다.
     */
    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        Map<String, Object> limits = new LinkedHashMap<>();
        Map<String, Object> rejections = new LinkedHashMap<>();
        Arrays.stream(CardCompany.values()).forEach(company -> {
            limits.put(company.name(), registry.getCurrentLimit(company));
            rejections.put(company.name(), registry.getPreRejections(company));
        });
        result.put("limit", limits);
        result.put("preRejected", rejections);
        // 표본화 스크립트가 기존 키를 계속 읽을 수 있도록 평면 키도 유지한다
        limits.forEach(result::put);
        return result;
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        registry.resetPreRejections();
        return ResponseEntity.noContent().build();
    }
}
