package com.example.paymentsystem.fds.service;

import com.example.paymentsystem.fds.domain.FraudCheck;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.repository.FraudCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotentService {

    private final IdempotentManager idempotentManager;
    private final FraudCheckRepository repository;

    public FraudCheck tryInsert(FraudCheckRequest request, String hash) {
        try {
            return idempotentManager.attemptInsert(request, hash);
        } catch (DataIntegrityViolationException e) {
            String idempotentKey = request.paymentKey() + ":fds";
            return repository.findByIdempotencyKey(idempotentKey)
                    .orElseThrow(() -> new IllegalStateException("fraud check not found"));
        }
    }
}
