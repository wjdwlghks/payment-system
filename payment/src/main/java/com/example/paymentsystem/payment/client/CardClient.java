package com.example.paymentsystem.payment.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class CardClient {

    @Qualifier("cardRestClient")
    private final RestClient cardRestClient;

    public CardAuthResponse authorize(CardAuthRequest request) {
        CardAuthResponse response = cardRestClient.post()
                .uri("/v1/authorizations")
                .body(request)
                .retrieve()
                .body(CardAuthResponse.class);
        return response;
    }

    public CardCaptureResponse capture(String authorizationId, CardCaptureRequest request) {
        CardCaptureResponse response = cardRestClient.post()
                .uri("/v1/authorizations/{authorizationId}/capture", authorizationId)
                .body(request)
                .retrieve()
                .body(CardCaptureResponse.class);
        return response;
    }
}
