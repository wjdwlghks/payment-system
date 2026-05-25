package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.PaymentApiResult;
import com.example.paymentsystem.payment.dto.RefundRequest;
import com.example.paymentsystem.payment.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @PostMapping("/refund")
    public ResponseEntity<String> refund(@RequestBody RefundRequest request) {
        PaymentApiResult result = refundService.refund(request);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }
}