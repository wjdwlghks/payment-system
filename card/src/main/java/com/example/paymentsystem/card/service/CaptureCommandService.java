package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CaptureResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaptureCommandService {

    private final CardAuthorizationRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApiResult capture(String authId, String idempotentKey, String hash) {
        CardAuthorization auth = getAuth(authId);

        // 순차 재요청: 최신 row가 이미 capture 되었을 수 있다.
        Optional<ApiResult> capturedResult = resolveCaptured(auth, idempotentKey, hash);
        if (capturedResult.isPresent()) {
            return capturedResult.get();
        }

        String captureId = "capture-" + UUID.randomUUID();
        auth.capture(captureId, idempotentKey, hash, Instant.now());
        entityManager.flush();

        return successResult(captureId);
    }

    @Transactional(readOnly = true)
    public ApiResult handleConflict(String authId, String idempotentKey, String hash) {
        CardAuthorization auth = getAuth(authId);

        // 동시 요청: 낙관적 락 충돌 후 최신 row를 다시 조회한다.
        Optional<ApiResult> capturedResult = resolveCaptured(auth, idempotentKey, hash);
        return capturedResult.orElseGet(() -> errorResult(409, "Capture Conflict"));

    }

    private Optional<ApiResult> resolveCaptured(CardAuthorization auth, String idempotentKey, String hash) {
        if (auth.getCaptureIdempotentKey() == null) {
            return Optional.empty();
        }

        if (auth.getCaptureIdempotentKey().equals(idempotentKey)) {
            if (!Objects.equals(auth.getCaptureHash(), hash)) {
                return Optional.of(errorResult(409, "Request Hash Mismatch"));
            }
            return Optional.of(successResult(auth.getCaptureId()));
        }

        return Optional.of(errorResult(409, "Already Captured"));
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

    private ApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new ApiResult(statusCode, responseBody);
    }
}
