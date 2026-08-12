package com.example.paymentsystem.merchant.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    private final RestClient paymentRestClient;

    public PaymentApiController(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    @PostMapping
    public ResponseEntity<String> requestPayment(@RequestBody String body) {
        return paymentRestClient.post()
                .uri("/v1/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {})
                .toEntity(String.class);
    }

    @PostMapping("/{paymentKey}/approve")
    public ResponseEntity<String> approvePayment(@PathVariable String paymentKey) {
        return paymentRestClient.post()
                .uri("/v1/payment/{paymentKey}/approve", paymentKey)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {})
                .toEntity(String.class);
    }
}
