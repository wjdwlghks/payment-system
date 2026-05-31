package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.IngestReconciliationRequest;
import com.example.paymentsystem.payment.dto.IngestReconciliationResponse;
import com.example.paymentsystem.payment.service.ReconciliationIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private final ReconciliationIngestService reconciliationIngestService;

    @PostMapping("/ingest")
    public ResponseEntity<IngestReconciliationResponse> ingest(
            @RequestBody IngestReconciliationRequest request
    ) {
        IngestReconciliationResponse response = reconciliationIngestService.ingest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
