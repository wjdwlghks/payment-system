package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.CardAuthRequest;
import com.example.paymentsystem.payment.client.CardAuthResponse;
import com.example.paymentsystem.payment.client.CardCaptureRequest;
import com.example.paymentsystem.payment.client.CardCaptureResponse;
import com.example.paymentsystem.payment.client.CardClient;
import com.example.paymentsystem.payment.client.fds.FdsCheckRequest;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.dto.PaymentRequest;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentCommandService paymentCommandService;
    private final CardClient cardClient;
    private final FdsClient fdsClient;

    public PaymentResponse requestPayment(PaymentRequest request, String idempotencyKey) {
        AuthRequestContext context = paymentCommandService.createAuthRequest(request, idempotencyKey);
        CardAuthResponse cardResponse = cardClient.authorize(new CardAuthRequest(
                context.paymentKey(),
                context.orderId(),
                context.merchantId(),
                context.amount()
        ));

        return paymentCommandService.completeAuth(
                context.paymentIntentId(),
                context.transactionId(),
                cardResponse
        );
    }

    public PaymentResponse confirmPayment(String paymentKey, String idempotencyKey) {
        FdsRequestContext context = paymentCommandService.createFdsRequest(paymentKey);
        FdsCheckResponse fdsResponse = fdsClient.check(new FdsCheckRequest(
                context.paymentKey(),
                context.orderId(),
                context.merchantId(),
                context.amount()
        ));

        if (!fdsResponse.success()) {
            return paymentCommandService.failFds(
                    context.paymentIntentId(),
                    context.transactionId(),
                    fdsResponse
            );
        }

        CaptureRequestContext captureContext = paymentCommandService.completeFdsAndCreateCaptureRequest(
                context.paymentIntentId(),
                context.transactionId(),
                fdsResponse,
                idempotencyKey
        );

        CardCaptureResponse captureResponse = cardClient.capture(
                captureContext.authorizationId(),
                new CardCaptureRequest(
                        captureContext.paymentKey(),
                        captureContext.orderId(),
                        captureContext.amount()
                )
        );

        return paymentCommandService.completeCapture(
                captureContext.paymentIntentId(),
                captureContext.transactionId(),
                captureResponse
        );
    }
}
