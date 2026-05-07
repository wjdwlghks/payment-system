package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.dto.CardApiResult;
import com.example.paymentsystem.card.service.CardAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/authorizations")
@RequiredArgsConstructor
public class CardAuthController {

    private final CardAuthService cardAuthService;

    @PostMapping
    public ResponseEntity<String> authorize(@RequestBody CardAuthRequest request) {
        CardApiResult result = cardAuthService.authorize(request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
