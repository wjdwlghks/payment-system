package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.PaymentRequest;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import com.example.paymentsystem.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public PaymentResponse requestPayment(
            @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return paymentService.requestPayment(request, idempotencyKey);
    }

    @PostMapping("/{paymentKey}/confirm")
    public PaymentResponse confirmPayment(
            @PathVariable String paymentKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return paymentService.confirmPayment(paymentKey, idempotencyKey);
    }
}
