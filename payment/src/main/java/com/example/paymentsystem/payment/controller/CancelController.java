package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.CancelRequest;
import com.example.paymentsystem.payment.dto.CancelResponse;
import com.example.paymentsystem.payment.service.CancelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class CancelController {

    private final CancelService cancelService;

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<CancelResponse> cancel(
            @PathVariable String paymentKey,
            @RequestBody CancelRequest request
    ) {
        CancelResponse response = cancelService.cancel(paymentKey, request);
        return ResponseEntity.ok(response);
    }
}
