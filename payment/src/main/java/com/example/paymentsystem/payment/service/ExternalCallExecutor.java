package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.ConcurrencyLimitExceededException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
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
        } catch (RestClientException e) {
            // 4xx만 카드사/FDS의 확정 거절이다 → 실패.
            // 그 외는 모두 처리 여부를 알 수 없다 → UNKNOWN → inquiry:
            //   - ResourceAccessException: read/connect timeout 구분 불가 (LB/proxy가 끼면 connect도 처리 후일 수 있음)
            //   - 5xx: 카드사가 받았는지 처리했는지 불명
            //   - UnknownContentTypeException 등: 응답을 받았으나 해석 실패
            //
            // Exception까지 넓히면 안 된다 — try가 onResponse(DB 쓰기·원장 기표)까지 감싸고 있어서
            // 낙관적 락 충돌이나 핸들러 버그가 "카드사 응답 불명"으로 오분류된다.
            boolean definitiveRejection = e instanceof RestClientResponseException response
                    && response.getStatusCode().is4xxClientError();
            return definitiveRejection ? onFailure.get() : onUnknown.get();
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
