package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.AuthRequest;
import com.example.paymentsystem.card.dto.AuthResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final IdempotentService idempotentService;
    private final ObjectMapper objectMapper;

    public ApiResult authorize(AuthRequest request) {

        String authHash = DigestUtils.sha256Hex(
            request.merchantId() + ":" + request.orderId() + ":" + request.amount()
        );

        CardAuthorization cardAuth = idempotentService.tryInsert(request, authHash);

        if (!cardAuth.getAuthHash().equals(authHash)) {
            return errorResult(409, "Request Hash Mismatch");
        }

        AuthResponse response = new AuthResponse(true, cardAuth.getAuthId(), cardAuth.getAuthorizedAt());
        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }

    private ApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new ApiResult(statusCode, responseBody);
    }
}
