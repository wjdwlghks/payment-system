package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.PaymentApiResult;
import com.example.paymentsystem.payment.dto.PaymentRequest;
import com.example.paymentsystem.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<String> authenticationPayment(@RequestBody PaymentRequest request) {
        PaymentApiResult result = paymentService.authenticationPayment(request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @PostMapping("/{paymentKey}/approve")
    public ResponseEntity<String> approvePayment(@PathVariable String paymentKey) {
        PaymentApiResult result = paymentService.approvePayment(paymentKey);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
