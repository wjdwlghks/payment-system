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
        String response = paymentRestClient.post()
                .uri("/v1/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    @PostMapping("/{paymentKey}/confirm")
    public ResponseEntity<String> confirmPayment(@PathVariable String paymentKey) {
        String response = paymentRestClient.post()
                .uri("/v1/payment/{paymentKey}/confirm", paymentKey)
                .retrieve()
                .body(String.class);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
