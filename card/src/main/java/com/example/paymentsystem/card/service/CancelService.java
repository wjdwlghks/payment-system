package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardAuthentication;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
import com.example.paymentsystem.card.domain.CardCancel;
import com.example.paymentsystem.card.domain.CardCancelStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CancelResponse;
import com.example.paymentsystem.card.dto.ErrorResponse;
import com.example.paymentsystem.card.repository.CardAuthenticationRepository;
import com.example.paymentsystem.card.repository.CardCancelRepository;
import com.example.paymentsystem.card.repository.CardCaptureRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 승인취소(void) — 매입 전 승인건을 되돌린다.
 *
 * <p>매입과 달리 <b>조회(inquiry) 엔드포인트가 없다.</b> PG가 취소를 UNKNOWN 없이
 * 확정 결과로만 다루기 때문이다 — 응답을 못 받으면 PG가 통째로 롤백하고 재요청한다.
 * 그래서 카드사도 "취소가 진행 중"인 상태를 노출할 필요가 없다.
 */
@Service
@RequiredArgsConstructor
public class CancelService {

    private final CardCancelRepository cancelRepository;
    private final CardCaptureRepository captureRepository;
    private final CardAuthenticationRepository authenticationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApiResult cancel(String approvalId, String cardRequestRef, Long amount) {
        CardAuthentication auth = authenticationRepository.findByApprovalId(approvalId)
                .orElse(null);
        if (auth == null || auth.getApprovalStatus() != CardApprovalStatus.SUCCESS) {
            return errorResult(404, "Approval not found: " + approvalId);
        }

        // 매입 여부는 PG 말이 아니라 카드사 원장이 답한다. PG가 매입 상태를 오판하거나
        // 매입과 취소가 진짜로 동시에 도착해도, 먼저 도착한 쪽이 이기고 나중은 여기서 막힌다.
        if (captureRepository.existsByApprovalId(approvalId)) {
            return errorResult(409, "Already captured; cancel not allowed: " + approvalId);
        }

        // 재요청은 처음 발급한 취소를 그대로 돌려준다. 카드사는 일반적으로 멱등을 보장하지
        // 않지만(인증·승인·매입은 도착하면 무조건 처리한다) 취소만은 예외다 —
        // PG가 카드사 응답을 못 받으면 롤백 후 같은 취소를 다시 보내도록 설계돼 있어서,
        // 여기서 두 번째 취소 행을 만들면 그 재요청이 영구히 409로 막힌다.
        CardCancel existing = cancelRepository.findByApprovalId(approvalId).orElse(null);
        if (existing != null) {
            return successResult(existing.getCancelId());
        }

        CardCancel cancel = CardCancel.builder()
                .cancelId("cancel-" + UUID.randomUUID())
                .approvalId(approvalId)
                .cardRequestRef(cardRequestRef)
                .amount(amount)
                .status(CardCancelStatus.SUCCESS)
                .canceledAt(Instant.now())
                .build();
        cancelRepository.save(cancel);

        return successResult(cancel.getCancelId());
    }

    private ApiResult successResult(String cancelId) {
        String body = objectMapper.writeValueAsString(new CancelResponse(true, cancelId));
        return new ApiResult(200, body);
    }

    private ApiResult errorResult(int statusCode, String message) {
        String body = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new ApiResult(statusCode, body);
    }
}
