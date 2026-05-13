package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardCaptureRequest;
import com.example.paymentsystem.payment.client.card.CardCaptureResponse;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaptureExecutionService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;

    @Transactional(readOnly = true)
    public List<PaymentIntent> getFdsReadyPaymentIntents() {
        return paymentIntentRepository.findTop3ByStatusOrderByUpdatedAtAsc(PaymentIntentStatus.FDS_READY);
    }

    public void capture(PaymentIntent paymentIntent) {
        CaptureRequestContext captureContext = paymentCommandService.createCaptureRequest(paymentIntent.getPaymentKey());
        captureWithoutRetry(captureContext);
    }

    public PaymentResponse captureWithRetry(CaptureRequestContext captureContext) {
        return capture(captureContext, true);
    }

    private PaymentResponse captureWithoutRetry(CaptureRequestContext captureContext) {
        return capture(captureContext, false);
    }

    private PaymentResponse capture(CaptureRequestContext captureContext, boolean retry) {
        CardCaptureRequest captureRequest = new CardCaptureRequest(
                captureContext.idempotentKey(),
                captureContext.orderId(),
                captureContext.amount()
        );

        return externalCallExecutor.execute(
                () -> capture(retry, captureContext, captureRequest),
                response -> response.success()
                        ? paymentCommandService.completeCapture(captureContext.transactionId(), response.externalId())
                        : paymentCommandService.failCapture(captureContext.transactionId(), response.externalId()),
                () -> paymentCommandService.unknownCapture(captureContext.transactionId()),
                () -> paymentCommandService.failCapture(captureContext.transactionId(), null)
        );
    }

    private CardCaptureResponse capture(
            boolean retry,
            CaptureRequestContext captureContext,
            CardCaptureRequest captureRequest
    ) {
        if (retry) {
            return cardClient.capture(captureContext.authorizationId(), captureRequest);
        }

        return cardClient.captureWithoutRetry(captureContext.authorizationId(), captureRequest);
    }
}
