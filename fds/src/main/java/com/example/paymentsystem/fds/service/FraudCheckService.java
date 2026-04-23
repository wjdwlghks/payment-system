package com.example.paymentsystem.fds.service;

import com.example.paymentsystem.fds.domain.FraudCheck;
import com.example.paymentsystem.fds.domain.FraudDecision;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.dto.FraudCheckResponse;
import com.example.paymentsystem.fds.repository.FraudCheckRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FraudCheckService {

    private final FraudCheckRepository fraudCheckRepository;

    @Transactional
    public FraudCheckResponse check(FraudCheckRequest request) {
        FraudCheck fraudCheck = fraudCheckRepository.save(new FraudCheck(
                request.paymentKey(),
                request.amount(),
                FraudDecision.APPROVE
        ));

        return new FraudCheckResponse(true, fraudCheck.getDecision().name(), "fds-" + UUID.randomUUID());
    }
}
