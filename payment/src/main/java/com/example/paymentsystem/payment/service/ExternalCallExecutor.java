package com.example.paymentsystem.payment.service;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class ExternalCallExecutor {

    public <T, R> R execute(
            Supplier<T> call,
            Function<T, R> onResponse,
            Supplier<R> onUnknown,
            Supplier<R> onFailure
    ) {
        T result;
        try {
            result = call.get();
        } catch (BulkheadFullException | CallNotPermittedException e) {
            // 로컬 거절 — 요청이 카드사로 나가지 않았음이 확실하다. 따라서 UNKNOWN이 아니라 실패다.
            //
            // UNKNOWN으로 흘리면 두 번 손해다: 이미 아픈 카드사에 재조회까지 얹게 되고,
            // 그 재조회는 어차피 not_found를 받아 결국 같은 실패로 끝난다.
            log.warn("Local rejection, request never left. cause={}", e.getClass().getSimpleName());
            return onFailure.get();
        } catch (RestClientException e) {
            // 4xx만 카드사/FDS의 확정 거절이다 → 실패.
            // 그 외는 모두 처리 여부를 알 수 없다 → UNKNOWN → inquiry:
            //   - ResourceAccessException: read/connect timeout 구분 불가 (LB/proxy가 끼면 connect도 처리 후일 수 있음)
            //   - 5xx: 카드사가 받았는지 처리했는지 불명
            //   - UnknownContentTypeException 등: 응답을 받았으나 해석 실패
            boolean definitiveRejection = e instanceof RestClientResponseException response
                    && response.getStatusCode().is4xxClientError();
            if (!definitiveRejection) {
                // UNKNOWN의 원인을 남긴다. 이걸 안 남기면 "카드사가 3초간 무응답"과
                // "우리 커넥션 풀이 말라 요청을 못 보냄"이 로그상 완전히 같아 보인다.
                // 앞의 것은 카드사 사정이고 뒤의 것은 우리 용량 문제라 대응이 정반대다.
                log.warn("External call unresolved -> UNKNOWN. cause={}", causeChain(e));
            }
            return definitiveRejection ? onFailure.get() : onUnknown.get();
        }
        return onResponse.apply(result);
    }

    /**
     * 예외 체인을 클래스명만으로 납작하게 만든다.
     *
     * <p>Spring이 전송 계층 {@code IOException}을 전부 {@code ResourceAccessException}으로 감싸기
     * 때문에 최상위 타입만으로는 아무것도 구분되지 않는다. 원인까지 따라가야
     * {@code ConnectionRequestTimeoutException}(풀 고갈 — 요청이 안 나갔다)과
     * {@code SocketTimeoutException}(카드사가 답을 안 했다)이 갈린다.
     */
    private String causeChain(Throwable throwable) {
        StringBuilder chain = new StringBuilder(throwable.getClass().getSimpleName());
        Throwable cause = throwable.getCause();
        // 순환 참조로 무한 루프에 빠지지 않도록 깊이를 제한한다.
        for (int depth = 0; cause != null && depth < 5; depth++) {
            chain.append(" <- ").append(cause.getClass().getSimpleName());
            cause = cause.getCause();
        }
        return chain.toString();
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
