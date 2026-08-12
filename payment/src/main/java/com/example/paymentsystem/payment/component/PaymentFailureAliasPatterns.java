package com.example.paymentsystem.payment.component;

import java.util.Map;
import java.util.Set;

public class PaymentFailureAliasPatterns {
    private static final Map<String, String> ALIASES = Map.of(
            "card_auth", "/v1/authentications",
            "card_approve", "/v1/authentications/",
            "card_capture", "/v1/approvals/",
            "fds_check", "/v1/fraud-checks"
    );

    public String resolveAlias(String requestPath) {
        return ALIASES.entrySet().stream()
                .filter(e -> requestPath.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public String resolvePath(String alias) {
        return ALIASES.get(alias);
    }

    public Set<String> validAliases() {
        return ALIASES.keySet();
    }
}
