package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.AuthRequest;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/authorizations")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping
    public ResponseEntity<String> authorize(@RequestBody AuthRequest request) {
        ApiResult result = authService.authorize(request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @GetMapping("/inquiries/{authIdempotentKey}")
    public ResponseEntity<String> inquire(@PathVariable String authIdempotentKey) {
        ApiResult result = authService.inquire(authIdempotentKey);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
