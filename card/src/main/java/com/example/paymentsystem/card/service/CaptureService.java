package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CaptureRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CaptureService {

    private final CaptureCommandService captureCommandService;

    public ApiResult capture(String authId, CaptureRequest request) {
        String captureHash = DigestUtils.sha256Hex(
                authId + ":" + request.orderId() + ":" + request.amount()
        );

        try {
            return captureCommandService.capture(authId, request.captureIdempotentKey(), captureHash);
        } catch (OptimisticLockingFailureException e) {
            return captureCommandService.handleConflict(authId, request.captureIdempotentKey(), captureHash);
        }
    }
}
