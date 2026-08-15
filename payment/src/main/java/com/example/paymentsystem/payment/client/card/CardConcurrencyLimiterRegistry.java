package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.domain.CardCompany;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CardConcurrencyLimiterRegistry {

    @Value("${card.limiter.min-limit:1}")
    private int minLimit;

    private final Map<CardCompany, SimpleLimiter<Void>> limiters = new EnumMap<>(CardCompany.class);
    private final Map<CardCompany, Gradient2Limit> limits = new EnumMap<>(CardCompany.class);

    /**
     * 사전거절 누적 수. 카드사에 요청이 나가지도 못하고 로컬에서 떨어진 건수다.
     *
     * <p>이게 필요한 이유: 사전거절은 {@code ExternalCallExecutor}에서 <b>확정 실패</b>로 분류돼
     * 곧바로 AUTH_FAILED가 된다(요청이 안 나갔으니 UNKNOWN이 아니라는 판단은 옳다).
     * 그런데 결과만 보면 카드사가 거절한 것과 구분이 안 돼서, 실패율이 높을 때
     * 원인이 카드사인지 우리 리미터인지 사후에 알 수가 없다.
     */
    private final Map<CardCompany, AtomicLong> preRejections = new EnumMap<>(CardCompany.class);

    @PostConstruct
    public void init() {
        for (CardCompany company : CardCompany.values()) {
            Gradient2Limit limit = Gradient2Limit.newBuilder()
                    .initialLimit(20)
                    .minLimit(minLimit)
                    .maxConcurrency(200)
                    .smoothing(0.2)
                    .build();

            limits.put(company, limit);
            preRejections.put(company, new AtomicLong());
            limiters.put(company, SimpleLimiter.<Void>newBuilder()
                    .limit(limit)
                    .build());
        }
    }

    public Optional<Limiter.Listener> acquire(CardCompany company) {
        Optional<Limiter.Listener> token = limiters.get(company).acquire(null);
        if (token.isEmpty()) {
            preRejections.get(company).incrementAndGet();
        }
        return token;
    }

    public long getPreRejections(CardCompany company) {
        return preRejections.get(company).get();
    }

    public void resetPreRejections() {
        preRejections.values().forEach(counter -> counter.set(0));
    }

    public int getCurrentLimit(CardCompany company) {
        return limits.get(company).getLimit();
    }
}
