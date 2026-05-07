package com.example.paymentsystem.fds.controller;

import com.example.paymentsystem.fds.dto.FdsApiResult;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.service.FraudCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/fraud-checks")
@RequiredArgsConstructor
public class FraudCheckController {

    private final FraudCheckService fraudCheckService;

    @PostMapping
    public ResponseEntity<String> check(@RequestBody FraudCheckRequest request) {
        FdsApiResult result = fraudCheckService.check(request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
