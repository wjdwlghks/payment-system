package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.dto.CaptureRunResponse;
import com.example.paymentsystem.payment.service.CaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/captures")
@RequiredArgsConstructor
public class CaptureAdminController {

    private final CaptureService captureService;

    @PostMapping("/run")
    public ResponseEntity<CaptureRunResponse> run(
            @RequestParam(defaultValue = "500") int limit
    ) {
        return ResponseEntity.ok(captureService.runCaptures(limit));
    }
}
