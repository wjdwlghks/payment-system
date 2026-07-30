package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardCaptureRequest;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.PaymentApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CaptureExecutionService {

    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;

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
                () -> paymentCommandService.unknownCaptureAndComplete(captureContext.transactionId(), idempotentKey),
                () -> paymentCommandService.failCaptureAndComplete(captureContext.transactionId(), null, idempotentKey)
        );
    }
}
