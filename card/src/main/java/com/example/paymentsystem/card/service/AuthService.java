package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.AuthInquiryResponse;
import com.example.paymentsystem.card.dto.AuthRequest;
import com.example.paymentsystem.card.dto.AuthResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final IdempotentService idempotentService;
    private final CardAuthorizationRepository repository;
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

    public ApiResult inquire(String authIdempotentKey) {
        AuthInquiryResponse response = repository.findByAuthIdempotentKey(authIdempotentKey)
                .map(this::toInquiryResponse)
                .orElseGet(() -> new AuthInquiryResponse("not_found", null, null));

        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }

    private AuthInquiryResponse toInquiryResponse(CardAuthorization cardAuth) {
        if (cardAuth.getAuthStatus() == CardAuthStatus.SUCCESS) {
            return new AuthInquiryResponse("success", cardAuth.getAuthId(), cardAuth.getAuthorizedAt());
        }

        if (cardAuth.getAuthStatus() == CardAuthStatus.FAILED) {
            return new AuthInquiryResponse("failed", cardAuth.getAuthId(), cardAuth.getAuthorizedAt());
        }

        return new AuthInquiryResponse("in_progress", cardAuth.getAuthId(), cardAuth.getAuthorizedAt());
    }

    private ApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new ApiResult(statusCode, responseBody);
    }
}
