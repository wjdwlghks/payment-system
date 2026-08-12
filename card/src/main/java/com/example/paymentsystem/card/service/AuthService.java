package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthentication;
import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.AuthInquiryResponse;
import com.example.paymentsystem.card.dto.AuthRequest;
import com.example.paymentsystem.card.dto.AuthResponse;
import com.example.paymentsystem.card.repository.CardAuthenticationRepository;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CardAuthenticationRepository repository;
    private final ObjectMapper objectMapper;

    // 카드사는 멱등을 보장하지 않는다: 도착한 요청은 무조건 처리한다 (dedup 없음).
    // cardRequestRef는 dedup 키가 아니라 inquiry가 이 거래를 찾기 위한 매칭 키다.
    @Transactional
    public ApiResult authenticate(AuthRequest request) {
        Instant authenticatedAt = Instant.now();
        CardAuthentication cardAuth = CardAuthentication.builder()
                .authId("auth-" + UUID.randomUUID())
                .cardRequestRef(request.cardRequestRef())
                .authStatus(CardAuthStatus.SUCCESS)
                .approvalStatus(CardApprovalStatus.NOT_STARTED)
                .authenticatedAt(authenticatedAt)
                .build();
        repository.save(cardAuth);

        AuthResponse response = new AuthResponse(true, cardAuth.getAuthId(), cardAuth.getAuthenticatedAt());
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

    private AuthInquiryResponse toInquiryResponse(CardAuthentication cardAuth) {
        if (cardAuth.getAuthStatus() == CardAuthStatus.SUCCESS) {
            return new AuthInquiryResponse("success", cardAuth.getAuthId(), cardAuth.getAuthenticatedAt());
        }

        if (cardAuth.getAuthStatus() == CardAuthStatus.FAILED) {
            return new AuthInquiryResponse("failed", cardAuth.getAuthId(), cardAuth.getAuthenticatedAt());
        }

        return new AuthInquiryResponse("in_progress", cardAuth.getAuthId(), cardAuth.getAuthenticatedAt());
    }
}
