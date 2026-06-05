package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.failure.FailureRegistry;
import com.example.paymentsystem.failure.FailureRule;
import com.example.paymentsystem.failure.FailureType;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;

@RequiredArgsConstructor
@Component
public class FailureSimulationInterceptor implements ClientHttpRequestInterceptor {

    private final FailureRegistry failureRegistry;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        FailureRule rule = failureRegistry.consumeForClientRequest(request);

        if (rule != null && rule.getFailure() == FailureType.CONNECT_FAILURE && rule.shouldTrigger()) {
            sleep(rule.getDelayMs());
            throw new ConnectTimeoutException("Simulated connect timeout");
        }

        return execution.execute(request, body);
    }

    private void sleep(long delayMs) throws InterruptedIOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("failure simulation interrupted");
        }
    }
}
