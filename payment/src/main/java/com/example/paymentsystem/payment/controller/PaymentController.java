package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.ConfirmApiResult;
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
    public ResponseEntity<String> requestPayment(@RequestBody PaymentRequest request) {
        PaymentApiResult result = paymentService.requestPayment(request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @PostMapping("/{paymentKey}/confirm")
    public ResponseEntity<String> confirmPayment(@PathVariable String paymentKey) {
        PaymentApiResult result = paymentService.confirmPayment(paymentKey);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
