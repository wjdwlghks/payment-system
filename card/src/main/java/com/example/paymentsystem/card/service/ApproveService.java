package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.ApproveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApproveService {

    private final ApproveCommandService approveCommandService;

    public ApiResult approve(String authId, ApproveRequest request) {
        return approveCommandService.approve(authId, request.cardRequestRef(), request.amount());
    }
}
