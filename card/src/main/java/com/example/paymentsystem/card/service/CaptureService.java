package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CaptureRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CaptureService {

    private final CaptureCommandService captureCommandService;

    public ApiResult capture(String authId, CaptureRequest request) {
        return captureCommandService.capture(authId, request.cardRequestRef());
    }
}
