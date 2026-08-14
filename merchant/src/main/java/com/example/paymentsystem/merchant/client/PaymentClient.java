package com.example.paymentsystem.merchant.client;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * merchant → payment 호출.
 *
 * <p>4xx/5xx에서 예외를 던지지 않고 {@code ResponseEntity}를 그대로 돌려준다.
 * 승인은 진행 중이면 409, 상태가 안 맞으면 422를 내는데 둘 다 "장애"가 아니라
 * 흐름 판정에 쓰는 정상적인 신호이기 때문이다.
 */
@Component
public class PaymentClient {

    private final RestClient paymentRestClient;

    public PaymentClient(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    public ResponseEntity<String> requestPayment(String body) {
        return paymentRestClient.post()
                .uri("/v1/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {})
                .toEntity(String.class);
    }

    public ResponseEntity<String> approve(String paymentKey) {
        return paymentRestClient.post()
                .uri("/v1/payment/{paymentKey}/approve", paymentKey)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {})
                .toEntity(String.class);
    }

    public ResponseEntity<String> capture(String paymentKey) {
        return paymentRestClient.post()
                .uri("/v1/payment/{paymentKey}/capture", paymentKey)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {})
                .toEntity(String.class);
    }
}
