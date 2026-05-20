package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.SettlementRunResponse;
import com.example.paymentsystem.payment.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/settlement-run")
@RequiredArgsConstructor
public class SettlementAdminController {

    private final SettlementService settlementService;

    @PostMapping
    public ResponseEntity<SettlementRunResponse> settlementRun() {
        SettlementRunResponse response = settlementService.settlementRun();
        return ResponseEntity.ok().body(response);
    }
}
