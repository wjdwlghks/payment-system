package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class IdempotentService {

    private final IdempotentKeyManager idempotentKeyManager;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public Optional<IdempotencyKey> tryInsert(String idempotentKey, IdempotencyOperation operation, String requestHash) {
        try {
            idempotentKeyManager.attemptInsert(idempotentKey, operation, requestHash);
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            return idempotencyKeyRepository.findByIdempotentKeyAndOperation(idempotentKey, operation);
        }
    }

    public void complete(String idempotentKey, IdempotencyOperation operation, int code, String responseBody) {
        idempotentKeyManager.complete(idempotentKey, operation, code, responseBody);
    }
}
