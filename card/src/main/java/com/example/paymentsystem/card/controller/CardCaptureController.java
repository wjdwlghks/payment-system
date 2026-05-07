package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.CardCaptureRequest;
import com.example.paymentsystem.card.dto.CardApiResult;
import com.example.paymentsystem.card.service.CardCaptureService;
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
public class CardCaptureController {

    private final CardCaptureService cardCaptureService;

    @PostMapping("/{authorizationId}/capture")
    public ResponseEntity<String> capture(
            @PathVariable String authorizationId,
            @RequestBody CardCaptureRequest request
    ) {
        CardApiResult result = cardCaptureService.capture(authorizationId, request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
