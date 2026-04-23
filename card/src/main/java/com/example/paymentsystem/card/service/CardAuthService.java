package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.dto.CardAuthResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CardAuthService {

    private final CardAuthorizationRepository cardAuthorizationRepository;

    @Transactional
    public CardAuthResponse authorize(CardAuthRequest request) {
        Instant authorizedAt = Instant.now();
        CardAuthorization authorization = cardAuthorizationRepository.save(new CardAuthorization(
                "auth-" + UUID.randomUUID(),
                request.paymentKey(),
                request.amount(),
                authorizedAt
        ));

        return new CardAuthResponse(true, authorization.getAuthId(), authorizedAt);
    }
}
