package com.example.paymentsystem.failure;


import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class FailureRegistry {

    private final FailureAliasPatterns aliasPatterns;
    private final ConcurrentHashMap<String, FailureRule> rules = new ConcurrentHashMap<>();

    public void register(String alias, FailureRule rule) {
        if (!aliasPatterns.contains(alias)) {
            throw new IllegalArgumentException(String.format("Alias '%s' does not exist", alias));
        }
        rules.put(alias, rule);
    }

    public FailureRule consumeForRequest(HttpServletRequest request) {
        String requestKey = request.getMethod() + " " + request.getRequestURI();

        for (Map.Entry<String, Pattern> entry : aliasPatterns.patterns().entrySet()) {
            if (entry.getValue().matcher(requestKey).matches()) {
                return consumeByAlias(entry.getKey());
            }
        }
        return null;
    }

    private FailureRule consumeByAlias(String alias) {
        FailureRule[] consumed = new FailureRule[1];
        rules.compute(alias, (k, rule) -> {
            if (rule == null) return null;
            consumed[0] = rule;
            return rule.decrementAndIsExhausted() ? null : rule;
        });
        return consumed[0];
    }

    public Map<String, FailureRule> snapshot() {
        return Map.copyOf(rules);
    }

    public void clear() {
        rules.clear();
    }
}
