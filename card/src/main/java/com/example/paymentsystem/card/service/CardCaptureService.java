package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.CardApiResult;
import com.example.paymentsystem.card.dto.CardCaptureRequest;
import com.example.paymentsystem.card.dto.CardCaptureResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CardCaptureService {

    private final CardAuthorizationRepository cardAuthorizationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CardApiResult capture(String authorizationId, CardCaptureRequest request) {
        CardAuthorization authorization = cardAuthorizationRepository.findByAuthId(authorizationId)
                .orElseThrow(() -> new IllegalArgumentException("authorization not found: " + authorizationId));

        String captureHash = DigestUtils.sha256Hex(
                authorizationId + ":" + request.orderId() + ":" + request.amount()
        );

        if (authorization.getCaptureIdempotentKey() != null) {
            if (authorization.getCaptureIdempotentKey().equals(request.captureIdempotentKey())) {
                if (!authorization.getCaptureHash().equals(captureHash)) {
                    return errorResult(409, "Request Hash Mismatch");
                }

                CardCaptureResponse response = new CardCaptureResponse(true, authorization.getCaptureId());
                String responseBody = objectMapper.writeValueAsString(response);
                return new CardApiResult(200, responseBody);
            }

            return errorResult(409, "Already Captured");
        }

        String captureId = "capture-" + UUID.randomUUID();
        authorization.capture(captureId, request.captureIdempotentKey(), captureHash, Instant.now());

        CardCaptureResponse response = new CardCaptureResponse(true, captureId);
        String responseBody = objectMapper.writeValueAsString(response);
        return new CardApiResult(200, responseBody);
    }

    private CardApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new CardApiResult(statusCode, responseBody);
    }
}
