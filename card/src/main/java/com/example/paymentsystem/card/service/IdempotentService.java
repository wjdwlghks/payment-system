package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.dto.AuthRequest;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotentService {

    private final IdempotentManager idempotentManager;
    private final CardAuthorizationRepository repository;

    public CardAuthorization tryInsert(AuthRequest request, String authHash) {
        try {
            return idempotentManager.attemptInsert(request, authHash);
        } catch (DataIntegrityViolationException e) {
            return repository.findByAuthIdempotentKey(request.authIdempotentKey())
                    .orElseThrow(() -> new IllegalStateException("card auth not found"));
        }
    }
}
