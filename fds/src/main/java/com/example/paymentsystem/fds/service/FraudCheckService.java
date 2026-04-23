package com.example.paymentsystem.fds.service;

import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.dto.FraudCheckResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class FraudCheckService {

    public FraudCheckResponse check(FraudCheckRequest request) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        return new FraudCheckResponse(success, success ? "APPROVE" : "REJECT", "fds-" + UUID.randomUUID());
    }
}
