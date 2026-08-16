package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.domain.CardCapture;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import com.example.paymentsystem.card.dto.ApiResult;
import com.example.paymentsystem.card.dto.CaptureInquiryResponse;
import com.example.paymentsystem.card.dto.CaptureResponse;
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

@Service
@RequiredArgsConstructor
public class CaptureService {

    private final CardCaptureRepository captureRepository;
    private final CardCancelRepository cancelRepository;
    private final CardAuthenticationRepository authenticationRepository;
    private final ObjectMapper objectMapper;

    // 카드사는 멱등을 보장하지 않는다: 도착한 매입 요청은 무조건 처리한다 (dedup 없음).
    // cardRequestRef는 dedup 키가 아니라 inquiry가 이 거래를 찾기 위한 매칭 키다.
    @Transactional
    public ApiResult capture(String approvalId, String cardRequestRef, Long amount) {
        authenticationRepository.findByApprovalId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        // 취소된 승인은 매입할 수 없다. PG도 같은 판정을 하지만 그쪽 상태를 믿지 않는다 —
        // 매입과 취소가 진짜로 동시에 도착하면 PG의 가드는 둘 다 통과시킬 수 있고,
        // 그때 누가 이길지는 여기서만 결정된다.
        if (cancelRepository.existsByApprovalId(approvalId)) {
            String body = objectMapper.writeValueAsString(
                    new ErrorResponse("Approval already canceled: " + approvalId));
            return new ApiResult(409, body);
        }

        CardCapture capture = CardCapture.builder()
                .captureId("capture-" + UUID.randomUUID())
                .approvalId(approvalId)
                .cardRequestRef(cardRequestRef)
                .amount(amount)
                .status(CardCaptureStatus.SUCCESS)
                .capturedAt(Instant.now())
                .build();
        captureRepository.save(capture);

        String body = objectMapper.writeValueAsString(new CaptureResponse(true, capture.getCaptureId()));
        return new ApiResult(200, body);
    }

    @Transactional(readOnly = true)
    public ApiResult inquire(String cardRequestRef) {
        CaptureInquiryResponse response = captureRepository.findByCardRequestRef(cardRequestRef)
                .map(this::toInquiryResponse)
                .orElseGet(() -> new CaptureInquiryResponse("not_found", null, null));

        return new ApiResult(200, objectMapper.writeValueAsString(response));
    }

    private CaptureInquiryResponse toInquiryResponse(CardCapture capture) {
        if (capture.getStatus() == CardCaptureStatus.SUCCESS) {
            return new CaptureInquiryResponse("success", capture.getCaptureId(), capture.getCapturedAt());
        }
        if (capture.getStatus() == CardCaptureStatus.FAILED) {
            return new CaptureInquiryResponse("failed", capture.getCaptureId(), capture.getCapturedAt());
        }
        return new CaptureInquiryResponse("in_progress", capture.getCaptureId(), capture.getCapturedAt());
    }
}
