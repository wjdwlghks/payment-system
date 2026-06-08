package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.domain.CardCompany;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class CardConcurrencyLimiterRegistry {

    private final Map<CardCompany, SimpleLimiter<Void>> limiters = new EnumMap<>(CardCompany.class);
    private final Map<CardCompany, Gradient2Limit> limits = new EnumMap<>(CardCompany.class);

    @PostConstruct
    public void init() {
        for (CardCompany company : CardCompany.values()) {
            Gradient2Limit limit = Gradient2Limit.newBuilder()
                    .initialLimit(20)
                    .minLimit(1)
                    .maxConcurrency(200)
                    .smoothing(0.2)
                    .build();

            limits.put(company, limit);
            limiters.put(company, SimpleLimiter.<Void>newBuilder()
                    .limit(limit)
                    .build());
        }
    }

    public Optional<Limiter.Listener> acquire(CardCompany company) {
        return limiters.get(company).acquire(null);
    }

    public int getCurrentLimit(CardCompany company) {
        return limits.get(company).getLimit();
    }
}
