package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.RefundRequest;
import com.example.paymentsystem.card.dto.RefundResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/authorizations")
public class RefundController {

    @PostMapping("/{captureId}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String captureId,
            @RequestBody RefundRequest request
    ) {
        return ResponseEntity.ok(new RefundResponse(
                true,
                UUID.randomUUID().toString(),
                Instant.now()
        ));
    }
}