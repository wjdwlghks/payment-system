package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CaptureInquiryResponse;
import com.example.paymentsystem.card.dto.CaptureResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaptureCommandService {

    private final CardAuthorizationRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    // 카드사는 멱등을 보장하지 않는다: 도착한 capture는 무조건 처리한다 (dedup 없음).
    @Transactional
    public ApiResult capture(String authId, String cardRequestRef) {
        CardAuthorization auth = getAuth(authId);

        String captureId = "capture-" + UUID.randomUUID();
        auth.capture(captureId, cardRequestRef, Instant.now());
        entityManager.flush();

        return successResult(captureId);
    }

    @Transactional(readOnly = true)
    public ApiResult inquire(String cardRequestRef) {
        CaptureInquiryResponse response = repository.findByCaptureCardRequestRef(cardRequestRef)
                .map(this::toInquiryResponse)
                .orElseGet(() -> new CaptureInquiryResponse("not_found", null, null));

        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }

    private CaptureInquiryResponse toInquiryResponse(CardAuthorization auth) {
        if (auth.getCaptureStatus() == CardCaptureStatus.SUCCESS) {
            return new CaptureInquiryResponse("success", auth.getCaptureId(), auth.getCapturedAt());
        }

        if (auth.getCaptureStatus() == CardCaptureStatus.FAILED) {
            return new CaptureInquiryResponse("failed", auth.getCaptureId(), auth.getCapturedAt());
        }

        return new CaptureInquiryResponse("in_progress", auth.getCaptureId(), auth.getCapturedAt());
    }

    private CardAuthorization getAuth(String authId) {
        return repository.findByAuthId(authId)
                .orElseThrow(() -> new IllegalArgumentException("Auth not found!"));
    }

    private ApiResult successResult(String captureId) {
        CaptureResponse response = new CaptureResponse(true, captureId);
        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }
}
