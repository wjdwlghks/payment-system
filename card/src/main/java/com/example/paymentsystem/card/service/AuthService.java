package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.AuthInquiryResponse;
import com.example.paymentsystem.card.dto.AuthRequest;
import com.example.paymentsystem.card.dto.AuthResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;
import com.example.paymentsystem.card.dto.VoidAuthResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CardAuthorizationRepository repository;
    private final ObjectMapper objectMapper;

    // 카드사는 멱등을 보장하지 않는다: 도착한 요청은 무조건 처리한다 (dedup 없음).
    // cardRequestRef는 dedup 키가 아니라 inquiry가 이 거래를 찾기 위한 매칭 키다.
    @Transactional
    public ApiResult authorize(AuthRequest request) {
        Instant authorizedAt = Instant.now();
        CardAuthorization cardAuth = CardAuthorization.builder()
                .authId("auth-" + UUID.randomUUID())
                .cardRequestRef(request.cardRequestRef())
                .amount(request.amount())
                .authStatus(CardAuthStatus.SUCCESS)
                .captureStatus(CardCaptureStatus.NOT_STARTED)
                .authorizedAt(authorizedAt)
                .build();
        repository.save(cardAuth);

        AuthResponse response = new AuthResponse(true, cardAuth.getAuthId(), cardAuth.getAuthorizedAt());
        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }

    public ApiResult inquire(String cardRequestRef) {
        AuthInquiryResponse response = repository.findByCardRequestRef(cardRequestRef)
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

    @Transactional
    public ApiResult voidAuth(String authId) {
        CardAuthorization cardAuth = repository.findByAuthId(authId)
                .orElse(null);
        if (cardAuth == null) {
            return errorResult(404, "Authorization not found: " + authId);
        }
        if (cardAuth.getAuthStatus() == CardAuthStatus.VOIDED) {
            String responseBody = objectMapper.writeValueAsString(new VoidAuthResponse(true, authId));
            return new ApiResult(200, responseBody);
        }
        try {
            cardAuth.markVoided();
        } catch (IllegalStateException e) {
            return errorResult(409, e.getMessage());
        }
        String responseBody = objectMapper.writeValueAsString(new VoidAuthResponse(true, authId));
        return new ApiResult(200, responseBody);
    }

    private ApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new ApiResult(statusCode, responseBody);
    }
}
