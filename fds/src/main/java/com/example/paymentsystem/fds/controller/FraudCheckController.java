package com.example.paymentsystem.fds.controller;

import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.dto.FraudCheckResponse;
import com.example.paymentsystem.fds.service.FraudCheckService;
import lombok.RequiredArgsConstructor;
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
    public FraudCheckResponse check(@RequestBody FraudCheckRequest request) {
        return fraudCheckService.check(request);
    }
}
