package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CancelRequest;
import com.example.paymentsystem.card.service.CancelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CancelController {

    private final CancelService cancelService;

    @PostMapping("/v1/approvals/{approvalId}/cancel")
    public ResponseEntity<String> cancel(
            @PathVariable String approvalId,
            @RequestBody CancelRequest request
    ) {
        ApiResult result = cancelService.cancel(approvalId, request.cardRequestRef(), request.amount());
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
