package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.ConcurrencyLimitExceededException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExternalCallExecutor {

    public <T, R> R execute(
            Supplier<T> call,
            Function<T, R> onResponse,
            Supplier<R> onUnknown,
            Supplier<R> onFailure
    ) {
        try {
            return onResponse.apply(call.get());
        } catch (ConcurrencyLimitExceededException e) {
            // 로컬 사전거절 — 요청이 아직 나가지 않았음이 확실 → 실패로 처리
            return onFailure.get();
        } catch (ResourceAccessException e) {
            // read/connect timeout을 구분할 수 없음 (LB/proxy가 끼면 connect도 처리 후일 수 있음)
            // → 처리 여부 불확실 → UNKNOWN → inquiry
            return onUnknown.get();
        } catch (RestClientResponseException e) {
            // 4xx: 카드사/FDS의 확정 응답 → 실패, 5xx: 처리 여부 불확실 → UNKNOWN → inquiry
            return e.getStatusCode().is4xxClientError() ? onFailure.get() : onUnknown.get();
        }
    }

    public <T> void executeVoid(
            Supplier<T> call,
            Consumer<T> onResponse,
            Runnable onUnknown,
            Runnable onFailure
    ) {
        execute(call, response -> {
            onResponse.accept(response);
            return null;
        }, () -> {
            onUnknown.run();
            return null;
        }, () -> {
            onFailure.run();
            return null;
        });
    }
}
