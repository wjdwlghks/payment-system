package com.example.paymentsystem.failure;


import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FailureRegistry {

    private final FailureAliasPatterns aliasPatterns;
    private final ConcurrentHashMap<String, List<FailureRule>> rules = new ConcurrentHashMap<>();

    public FailureRule consumeForRequest(HttpServletRequest request) {
        return consumeByMethodAndPath(request.getMethod(), request.getRequestURI());
    }

    public FailureRule consumeForClientRequest(HttpRequest request) {
        return consumeByMethodAndPath(request.getMethod().name(), request.getURI().getPath());
    }

    private FailureRule consumeByMethodAndPath(String method, String path) {
        String requestKey = method + " " + path;
        for (Map.Entry<String, Pattern> entry : aliasPatterns.patterns().entrySet()) {
            if (entry.getValue().matcher(requestKey).matches()) {
                return consumeByAlias(entry.getKey());
            }
        }
        return null;
    }

    public void register(String alias, FailureRule rule) {
        if (!aliasPatterns.contains(alias)) {
            throw new IllegalArgumentException(String.format("Alias '%s' does not exist", alias));
        }
        rules.compute(alias, (k, list) -> {
            List<FailureRule> result = (list != null) ? list : new ArrayList<>();
            result.add(rule);
            return result;
        });
    }

    // remaining은 총 시도 횟수 기준으로 감소 (shouldTrigger 여부 무관)
    // 여러 룰 중 shouldTrigger()를 통과한 첫 번째 룰을 반환
    private FailureRule consumeByAlias(String alias) {
        FailureRule[] triggered = {null};
        rules.compute(alias, (k, ruleList) -> {
            if (ruleList == null || ruleList.isEmpty()) return null;
            for (FailureRule rule : ruleList) {
                rule.decrementAndIsExhausted();
                if (triggered[0] == null && rule.shouldTrigger()) {
                    triggered[0] = rule;
                }
            }
            ruleList.removeIf(r -> r.remainingSnapshot() <= 0);
            return ruleList.isEmpty() ? null : ruleList;
        });
        return triggered[0];
    }

    public Map<String, List<FailureRule>> snapshot() {
        return rules.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    public void clear() {
        rules.clear();
    }
}
