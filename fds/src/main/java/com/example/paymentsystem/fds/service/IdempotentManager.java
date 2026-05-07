package com.example.paymentsystem.fds.service;


import com.example.paymentsystem.fds.domain.FraudCheck;
import com.example.paymentsystem.fds.domain.FraudDecision;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.repository.FraudCheckRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotentManager {

    private final FraudCheckRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FraudCheck attemptInsert(FraudCheckRequest request, String hash) {
        String fdsId = "fds-" + UUID.randomUUID();
        FraudCheck fraudCheck = new FraudCheck(
                fdsId,
                hash,
                request.paymentKey(),
                request.amount(),
                FraudDecision.APPROVE
        );

        return repository.saveAndFlush(fraudCheck);
    }
}
