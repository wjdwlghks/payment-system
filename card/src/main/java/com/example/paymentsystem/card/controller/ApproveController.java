package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.ApproveRequest;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.service.ApproveService;
import com.example.paymentsystem.card.service.ApproveCommandService;
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
@RequestMapping("/v1/authentications")
@RequiredArgsConstructor
public class ApproveController {

    private final ApproveService approveService;
    private final ApproveCommandService approveCommandService;

    @PostMapping("/{authenticationId}/approve")
    public ResponseEntity<String> approve(
            @PathVariable String authenticationId,
            @RequestBody ApproveRequest request
    ) {
        ApiResult result = approveService.approve(authenticationId, request);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @GetMapping("/approvals/inquiries/{cardRequestRef}")
    public ResponseEntity<String> inquire(@PathVariable String cardRequestRef) {
        ApiResult result = approveCommandService.inquire(cardRequestRef);
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
