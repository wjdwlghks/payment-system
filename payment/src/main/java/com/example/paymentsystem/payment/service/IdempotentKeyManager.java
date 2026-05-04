package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.example.paymentsystem.payment.domain.IdempotentStatus.PROCESSING;

@Service
@RequiredArgsConstructor
public class IdempotentKeyManager {

    private final IdempotencyKeyRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptInsert(String idempotentKey, IdempotencyOperation operation, String requestHash) {
        IdempotencyKey idempotencyKey = IdempotencyKey.builder()
                .idempotentKey(idempotentKey)
                .requestHash(requestHash)
                .operation(operation)
                .status(PROCESSING)
                .build();

        repository.saveAndFlush(idempotencyKey);
    }

    @Transactional
    public void complete(String idempotentKey, IdempotencyOperation operation, int code, String responseBody) {
        IdempotencyKey idempotencyKey = repository.findByIdempotentKeyAndOperation(idempotentKey, operation)
                .orElseThrow(() -> new IllegalStateException("idempotency key not found"));

        idempotencyKey.complete(code, responseBody);
    }

}
