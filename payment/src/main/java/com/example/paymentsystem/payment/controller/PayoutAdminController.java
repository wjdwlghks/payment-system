package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.PayoutResponse;
import com.example.paymentsystem.payment.service.PayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/payouts")
@RequiredArgsConstructor
public class PayoutAdminController {

    private final PayoutService payoutService;

    @PostMapping("/{merchantId}")
    public ResponseEntity<PayoutResponse> payout(@PathVariable String merchantId) {
        PayoutResponse response = payoutService.payout(merchantId);

        if (!response.created()) {
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
