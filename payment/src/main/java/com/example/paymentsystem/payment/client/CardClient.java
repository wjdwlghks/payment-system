package com.example.paymentsystem.payment.client;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CardClient {

    @Qualifier("cardRestClient")
    private final RestClient cardRestClient;

    private final Retry cardAuthRetry;
    private final Retry cardCaptureRetry;

    public CardAuthResponse authorize(CardAuthRequest request) {
        Supplier<CardAuthResponse> supplier = () ->
                cardRestClient.post()
                        .uri("/v1/authorizations")
                        .body(request)
                        .retrieve()
                        .body(CardAuthResponse.class);

        return Retry.decorateSupplier(cardAuthRetry, supplier).get();
    }

    public CardCaptureResponse capture(String authorizationId, CardCaptureRequest request) {
        Supplier<CardCaptureResponse> supplier = () ->
                cardRestClient.post()
                        .uri("/v1/authorizations/{authorizationId}/capture", authorizationId)
                        .body(request)
                        .retrieve()
                        .body(CardCaptureResponse.class);

        return Retry.decorateSupplier(cardCaptureRetry, supplier).get();
    }
}
