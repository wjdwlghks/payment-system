package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CaptureRequest;
import com.example.paymentsystem.card.service.CaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CaptureController {

    private final CaptureService captureService;

    @PostMapping("/v1/approvals/{approvalId}/capture")
    public ResponseEntity<String> capture(
            @PathVariable String approvalId,
            @RequestBody CaptureRequest request
    ) {
        ApiResult result = captureService.capture(approvalId, request.cardRequestRef(), request.amount());
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @GetMapping("/v1/captures/inquiries/{cardRequestRef}")
    public ResponseEntity<String> inquire(@PathVariable String cardRequestRef) {
        ApiResult result = captureService.inquire(cardRequestRef);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
