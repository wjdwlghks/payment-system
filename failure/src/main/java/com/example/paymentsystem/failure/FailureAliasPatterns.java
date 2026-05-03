package com.example.paymentsystem.failure;

import java.util.Map;
import java.util.regex.Pattern;

public record FailureAliasPatterns(Map<String, Pattern> patterns) {
    public FailureAliasPatterns {
        if (patterns == null || patterns.isEmpty()) {
            throw new IllegalArgumentException("patterns cannot be null or empty");
        }
        patterns = Map.copyOf(patterns);
    }

    public boolean contains(String alias) {
        return patterns.containsKey(alias);
    }
}
