package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.CardApiResult;
import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.dto.CardAuthResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CardAuthService {

    private final IdempotentService idempotentService;
    private final ObjectMapper objectMapper;

    public CardApiResult authorize(CardAuthRequest request) {

        String authHash = DigestUtils.sha256Hex(
            request.merchantId() + ":" + request.orderId() + ":" + request.amount()
        );

        CardAuthorization cardAuth = idempotentService.tryInsert(request, authHash);

        if (!cardAuth.getAuthHash().equals(authHash)) {
            return errorResult(409, "Request Hash Mismatch");
        }

        CardAuthResponse response = new CardAuthResponse(true, cardAuth.getAuthId(), cardAuth.getAuthorizedAt());
        String responseBody = objectMapper.writeValueAsString(response);
        return new CardApiResult(200, responseBody);
    }

    private CardApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new CardApiResult(statusCode, responseBody);
    }
}
