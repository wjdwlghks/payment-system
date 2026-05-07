package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.CaptureRequest;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.service.CaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/authorizations")
@RequiredArgsConstructor
public class CaptureController {

    private final CaptureService captureService;

    @PostMapping("/{authorizationId}/capture")
    public ResponseEntity<String> capture(
            @PathVariable String authorizationId,
            @RequestBody CaptureRequest request
    ) {
        ApiResult result = captureService.capture(authorizationId, request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
