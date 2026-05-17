package com.example.paymentsystem.merchant.controller;

import com.example.paymentsystem.merchant.dto.PaymentWebhookRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/payment")
@Slf4j
public class PaymentWebhookController {

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody PaymentWebhookRequest request) {
        log.info("Received payment webhook. eventId={}, eventType={}, paymentKey={}, status={}, failedStage={}",
                request.eventId(),
                request.eventType(),
                request.paymentKey(),
                request.status(),
                request.failedStage()
        );
        return ResponseEntity.noContent().build();
    }
}
