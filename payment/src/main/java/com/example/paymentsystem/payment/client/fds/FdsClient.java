package com.example.paymentsystem.payment.client.fds;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class FdsClient {

    @Qualifier("fdsRestClient")
    private final RestClient fdsRestClient;

    public FdsCheckResponse check(FdsCheckRequest request) {
        return fdsRestClient.post()
                .uri("/v1/fraud-checks")
                .body(request)
                .retrieve()
                .body(FdsCheckResponse.class);
    }
}
