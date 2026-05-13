package com.example.paymentsystem.payment.client.fds;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class FdsClient {

    @Qualifier("fdsRestClient")
    private final RestClient fdsRestClient;
    private final Retry fdsCheckRetry;

    public FdsCheckResponse check(FdsCheckRequest request) {
        Supplier<FdsCheckResponse> supplier = () ->
                fdsRestClient.post()
                        .uri("/v1/fraud-checks")
                        .body(request)
                        .retrieve()
                        .body(FdsCheckResponse.class);

        return Retry.decorateSupplier(fdsCheckRetry, supplier).get();
    }

    public FdsInquiryResponse inquiry(String idempotencyKey) {
        return fdsRestClient.get()
                .uri("/v1/fraud-checks/inquiries/{idempotencyKey}", idempotencyKey)
                .retrieve()
                .body(FdsInquiryResponse.class);
    }
}
