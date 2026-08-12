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

    private final CaptureCommandService captureCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;

    public PaymentApiResult capture(CaptureRequestContext context) {
        CardCaptureRequest request = new CardCaptureRequest(context.cardRequestRef(), context.amount());

        return externalCallExecutor.execute(
                () -> cardClient.capture(context.cardCompany(), context.approvalId(), request),
                response -> response.success()
                        ? captureCommandService.completeCapture(
                                context.transactionId(), response.externalId(), LedgerSourceType.PAYMENT_TRANSACTION)
                        : captureCommandService.failCapture(context.transactionId(), response.externalId()),
                () -> captureCommandService.unknownCapture(context.transactionId()),
                () -> captureCommandService.failCapture(context.transactionId(), null)
        );
    }
}
