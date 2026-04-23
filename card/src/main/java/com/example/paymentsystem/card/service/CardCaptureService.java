package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.CardCaptureRequest;
import com.example.paymentsystem.card.dto.CardCaptureResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CardCaptureService {

    private final CardAuthorizationRepository cardAuthorizationRepository;

    @Transactional
    public CardCaptureResponse capture(String authorizationId, CardCaptureRequest request) {
        CardAuthorization authorization = cardAuthorizationRepository.findByAuthId(authorizationId)
                .orElseThrow(() -> new IllegalArgumentException("authorization not found: " + authorizationId));

        authorization.capture(request.paymentKey(), Instant.now());

        return new CardCaptureResponse(true, "capture-" + UUID.randomUUID());
    }
}
