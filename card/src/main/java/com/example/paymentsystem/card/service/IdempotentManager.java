package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotentManager {

    private final CardAuthorizationRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CardAuthorization attemptInsert(CardAuthRequest request, String authHash) {
        Instant authorizedAt = Instant.now();
        CardAuthorization authorization = CardAuthorization.builder()
                .authId("auth-" + UUID.randomUUID())
                .authIdempotentKey(request.authIdempotentKey())
                .authHash(authHash)
                .amount(request.amount())
                .authorizedAt(authorizedAt)
                .build();

        return repository.saveAndFlush(authorization);
    }
}
