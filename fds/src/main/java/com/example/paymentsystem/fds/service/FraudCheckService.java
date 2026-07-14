package com.example.paymentsystem.fds.service;

import com.example.paymentsystem.fds.domain.FraudCheck;
import com.example.paymentsystem.fds.domain.FraudDecision;
import com.example.paymentsystem.fds.dto.FdsApiResult;
import com.example.paymentsystem.fds.dto.FraudCheckInquiryResponse;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.dto.FraudCheckResponse;
import com.example.paymentsystem.fds.repository.FraudCheckRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class FraudCheckService {

    private final FraudCheckRepository fraudCheckRepository;
    private final ObjectMapper objectMapper;

    // FDS도 멱등을 보장하지 않는다: 도착한 요청은 무조건 처리한다 (dedup 없음).
    // requestRef는 dedup 키가 아니라 inquiry가 이 체크를 찾기 위한 매칭 키다.
    @Transactional
    public FdsApiResult check(FraudCheckRequest request) {
        FraudCheck fraudCheck = new FraudCheck(
                request.requestRef(),
                "fds-" + UUID.randomUUID(),
                request.paymentKey(),
                request.amount(),
                FraudDecision.APPROVE
        );
        fraudCheckRepository.save(fraudCheck);

        FraudCheckResponse response = new FraudCheckResponse(true, fraudCheck.getDecision().name(), fraudCheck.getFdsId());
        String responseBody = objectMapper.writeValueAsString(response);
        return new FdsApiResult(200, responseBody);
    }

    public FdsApiResult inquire(String requestRef) {
        FraudCheckInquiryResponse response = fraudCheckRepository.findByRequestRef(requestRef)
                .map(this::toInquiryResponse)
                .orElseGet(() -> new FraudCheckInquiryResponse("not_found", null, null));

        String responseBody = objectMapper.writeValueAsString(response);
        return new FdsApiResult(200, responseBody);
    }

    private FraudCheckInquiryResponse toInquiryResponse(FraudCheck fraudCheck) {
        if (fraudCheck.getDecision() == FraudDecision.APPROVE) {
            return new FraudCheckInquiryResponse("success", fraudCheck.getDecision().name(), fraudCheck.getFdsId());
        }

        return new FraudCheckInquiryResponse("failed", fraudCheck.getDecision().name(), fraudCheck.getFdsId());
    }
}
