package com.example.paymentsystem.payment.config;

import com.example.paymentsystem.payment.component.RecoveryCounter;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;

@Configuration
public class ResilienceConfig {

    private RetryConfig defaultRetryConfig() {

        return RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(200), 2.0, 0.5))
                .retryOnException(throwable -> {
                    // Connect timeout: 도달 못 함이 확실 → retry
                    if (isCausedBy(throwable, ConnectTimeoutException.class)) return true;

                    // Read timeout: 처리 여부 불확실 → retry X, inquiry
                    if (isCausedBy(throwable, SocketTimeoutException.class)) return false;

                    // 5xx: 도달 후 응답 직전 사망 가능 → inquiry
                    if (isCausedBy(throwable, RestClientResponseException.class)) return false;

                    return false;
                })
                .build();
    }

    @Bean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.of(defaultRetryConfig());
    }

    @Bean
    public Retry cardAuthRetry(RetryRegistry registry, RecoveryCounter counter) {
        return withListeners(registry.retry("cardAuth"), "cardAuth", counter);
    }

    @Bean
    public Retry cardCaptureRetry(RetryRegistry registry, RecoveryCounter counter) {
        return withListeners(registry.retry("cardCapture"), "cardCapture", counter);
    }

    @Bean
    public Retry cardRefundRetry(RetryRegistry registry, RecoveryCounter counter) {
        return withListeners(registry.retry("cardRefund"), "cardRefund", counter);
    }

    @Bean
    public Retry fdsCheckRetry(RetryRegistry registry, RecoveryCounter counter) {
        return withListeners(registry.retry("fdsCheck"), "fdsCheck", counter);
    }

    private Retry withListeners(Retry retry, String name, RecoveryCounter counter) {
        retry.getEventPublisher()
                .onRetry(e -> counter.incrementRetryAttempt(name))
                .onSuccess(e -> { if (e.getNumberOfRetryAttempts() > 0) counter.incrementRetrySucceeded(name); })
                .onError(e -> { if (e.getNumberOfRetryAttempts() > 0) counter.incrementRetryFailed(name); });
        return retry;
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
