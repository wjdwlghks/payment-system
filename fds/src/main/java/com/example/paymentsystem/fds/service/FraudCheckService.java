package com.example.paymentsystem.fds.service;

import com.example.paymentsystem.fds.domain.FraudCheck;
import com.example.paymentsystem.fds.dto.ErrorResponse;
import com.example.paymentsystem.fds.dto.FdsApiResult;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.dto.FraudCheckResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class FraudCheckService {

    private final IdempotentService idempotentService;
    private final ObjectMapper objectMapper;


    public FdsApiResult check(FraudCheckRequest request) {
        String hash = DigestUtils.sha256Hex(
                request.paymentKey() + ":" + request.orderId() + ":" + request.merchantId() + ":" + request.amount()
        );

        FraudCheck fraudCheck = idempotentService.tryInsert(request, hash);

        if (!fraudCheck.getHash().equals(hash)) {
            return errorResult(409, "Request Hash Mismatch");
        }

        FraudCheckResponse response = new FraudCheckResponse(true, fraudCheck.getDecision().name(), fraudCheck.getFdsId());
        String responseBody = objectMapper.writeValueAsString(response);
        return new FdsApiResult(200, responseBody);
    }

    private FdsApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new FdsApiResult(statusCode, responseBody);
    }
}
