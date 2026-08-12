package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardCaptureRequest;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.CaptureBatch;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.CaptureRunResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 매입 배치 오케스트레이션. admin 엔드포인트 호출 1회 = 배치 1개.
 * 카드사 호출은 건별이며, 응답이 불확실하면 UNKNOWN으로 두고 InquiryScheduler가 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureService {

    private final CaptureCommandService captureCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;

    public CaptureRunResponse runCaptures(int limit) {
        CaptureBatch batch = captureCommandService.createBatch();
        List<Long> intentIds = captureCommandService.findTargets(limit);

        for (Long intentId : intentIds) {
            CaptureRequestContext context;
            try {
                context = captureCommandService.createCaptureRequest(intentId, batch.getId());
            } catch (Exception e) {
                // 동시 실행으로 이미 매입 tx가 생겼거나(UNIQUE 위반) 대상이 사라진 경우 — 건너뛴다.
                log.warn("Failed to create capture request. paymentIntentId={}: {}", intentId, e.toString());
                continue;
            }
            capture(context);
        }

        return captureCommandService.completeBatch(batch.getId());
    }

    private void capture(CaptureRequestContext context) {
        CardCaptureRequest request = new CardCaptureRequest(context.cardRequestRef(), context.amount());
        externalCallExecutor.executeVoid(
                () -> cardClient.capture(context.cardCompany(), context.approvalId(), request),
                response -> {
                    if (response.success()) {
                        captureCommandService.completeCapture(
                                context.transactionId(), response.externalId(), LedgerSourceType.PAYMENT_TRANSACTION);
                    } else {
                        captureCommandService.failCapture(context.transactionId(), response.externalId());
                    }
                },
                () -> captureCommandService.unknownCapture(context.transactionId()),
                () -> captureCommandService.failCapture(context.transactionId(), null)
        );
    }
}
