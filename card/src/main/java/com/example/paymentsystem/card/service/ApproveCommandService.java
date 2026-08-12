package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthentication;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.ApproveInquiryResponse;
import com.example.paymentsystem.card.dto.ApproveResponse;
import com.example.paymentsystem.card.repository.CardAuthenticationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApproveCommandService {

    private final CardAuthenticationRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    // 카드사는 멱등을 보장하지 않는다: 도착한 승인 요청은 무조건 처리한다 (dedup 없음).
    @Transactional
    public ApiResult approve(String authId, String cardRequestRef, Long amount) {
        CardAuthentication auth = getAuth(authId);

        String approvalId = "approval-" + UUID.randomUUID();
        auth.approve(approvalId, cardRequestRef, amount, Instant.now());
        entityManager.flush();

        return successResult(approvalId);
    }

    @Transactional(readOnly = true)
    public ApiResult inquire(String cardRequestRef) {
        ApproveInquiryResponse response = repository.findByApprovalCardRequestRef(cardRequestRef)
                .map(this::toInquiryResponse)
                .orElseGet(() -> new ApproveInquiryResponse("not_found", null, null));

        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }

    private ApproveInquiryResponse toInquiryResponse(CardAuthentication auth) {
        if (auth.getApprovalStatus() == CardApprovalStatus.SUCCESS) {
            return new ApproveInquiryResponse("success", auth.getApprovalId(), auth.getApprovedAt());
        }

        if (auth.getApprovalStatus() == CardApprovalStatus.FAILED) {
            return new ApproveInquiryResponse("failed", auth.getApprovalId(), auth.getApprovedAt());
        }

        return new ApproveInquiryResponse("in_progress", auth.getApprovalId(), auth.getApprovedAt());
    }

    private CardAuthentication getAuth(String authId) {
        return repository.findByAuthId(authId)
                .orElseThrow(() -> new IllegalArgumentException("Auth not found!"));
    }

    private ApiResult successResult(String approvalId) {
        ApproveResponse response = new ApproveResponse(true, approvalId);
        String responseBody = objectMapper.writeValueAsString(response);
        return new ApiResult(200, responseBody);
    }
}
