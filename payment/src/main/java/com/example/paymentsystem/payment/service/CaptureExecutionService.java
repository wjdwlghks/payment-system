package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardCaptureRequest;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.PaymentApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CaptureExecutionService {

    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;
    private final ObjectMapper objectMapper;

    public PaymentApiResult captureWithRetry(CaptureRequestContext captureContext, String idempotentKey) {
        CardCaptureRequest captureRequest = new CardCaptureRequest(
                captureContext.cardRequestRef(),
                captureContext.orderId(),
                captureContext.amount()
        );

        return externalCallExecutor.execute(
                () -> cardClient.capture(captureContext.cardCompany(), captureContext.authorizationId(), captureRequest),
                response -> response.success()
                        ? paymentCommandService.completeCaptureAndComplete(captureContext.transactionId(), response.externalId(), idempotentKey, LedgerSourceType.PAYMENT_TRANSACTION)
                        : paymentCommandService.failCaptureAndComplete(captureContext.transactionId(), response.externalId(), idempotentKey),
                // UNKNOWN은 확정된 결과가 아니므로 멱등키를 완결하지 않는다 — PROCESSING을 유지한 채
                // InquiryScheduler가 실제 상태를 확정하는 시점에 그 트랜잭션에서 완결된다.
                () -> new PaymentApiResult(200, objectMapper.writeValueAsString(
                        paymentCommandService.unknownCapture(captureContext.transactionId()))),
                () -> paymentCommandService.failCaptureAndComplete(captureContext.transactionId(), null, idempotentKey)
        );
    }
}
