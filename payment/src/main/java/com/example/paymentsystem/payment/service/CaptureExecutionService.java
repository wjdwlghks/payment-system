package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardCaptureRequest;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CaptureExecutionService {

    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;

    public PaymentResponse captureWithRetry(CaptureRequestContext captureContext) {
        CardCaptureRequest captureRequest = new CardCaptureRequest(
                captureContext.cardRequestRef(),
                captureContext.orderId(),
                captureContext.amount()
        );

        return externalCallExecutor.execute(
                () -> cardClient.capture(captureContext.cardCompany(), captureContext.authorizationId(), captureRequest),
                response -> response.success()
                        ? paymentCommandService.completeCapture(captureContext.transactionId(), response.externalId(), LedgerSourceType.PAYMENT_TRANSACTION)
                        : paymentCommandService.failCapture(captureContext.transactionId(), response.externalId()),
                () -> paymentCommandService.unknownCapture(captureContext.transactionId()),
                () -> paymentCommandService.failCapture(captureContext.transactionId(), null)
        );
    }
}
