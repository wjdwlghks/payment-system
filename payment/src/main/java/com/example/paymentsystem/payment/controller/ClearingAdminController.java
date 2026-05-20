package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.ClearingBatchResponse;
import com.example.paymentsystem.payment.service.ClearingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/clearing-batches")
@RequiredArgsConstructor
public class ClearingAdminController {

    private final ClearingService clearingService;

    @PostMapping
    public ResponseEntity<ClearingBatchResponse> clearingBatches() {
        ClearingBatchResponse response = clearingService.clearing();

        if (!response.created()) {
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
