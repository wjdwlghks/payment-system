package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.CardAuthRequest;
import com.example.paymentsystem.payment.client.CardAuthResponse;
import com.example.paymentsystem.payment.client.CardCaptureRequest;
import com.example.paymentsystem.payment.client.CardCaptureResponse;
import com.example.paymentsystem.payment.client.CardClient;
import com.example.paymentsystem.payment.client.fds.FdsCheckRequest;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.dto.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentCommandService paymentCommandService;
    private final IdempotentService idempotentService;
    private final CardClient cardClient;
    private final FdsClient fdsClient;
    private final ObjectMapper objectMapper;

    public PaymentApiResult requestPayment(PaymentRequest request) {

        String idempotentKey = request.merchantId() + ":" + request.orderId();
        String requestHash = DigestUtils.sha256Hex(
                request.merchantId() + ":" + request.orderId() + ":" + request.amount()
        );

        Optional<IdempotencyKey> existing = idempotentService.tryInsert(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, requestHash);

        if (existing.isPresent()) {
            IdempotencyKey e = existing.get();

            if (!e.getRequestHash().equals(requestHash)) {
                return errorResult(409, "Request Hash Mismatch");
            }

            if (e.getStatus() == IdempotentStatus.PROCESSING) {
                return errorResult(409, "Processing Request");
            }

            return new PaymentApiResult(e.getResponseCode(), e.getResponseBody());
        }

        AuthRequestContext context = paymentCommandService.createAuthRequest(request);
        String authIdempotentKey = context.paymentKey() + ":auth";
        CardAuthResponse cardResponse = cardClient.authorize(new CardAuthRequest(
                authIdempotentKey,
                context.orderId(),
                context.merchantId(),
                context.amount()
        ));

        PaymentResponse paymentResponse = paymentCommandService.completeAuth(
                context.paymentKey(),
                context.transactionId(),
                cardResponse
        );

        String responseBody = objectMapper.writeValueAsString(paymentResponse);
        idempotentService.complete(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, 200, responseBody);

        return new PaymentApiResult(200, responseBody);
    }

    public PaymentApiResult confirmPayment(String paymentKey) {

        PaymentIntent paymentIntent = paymentCommandService.getPaymentIntent(paymentKey);
        String idempotentKey = paymentIntent.getMerchantId() + ":" + paymentKey;
        String requestHash = DigestUtils.sha256Hex(idempotentKey);

        Optional<IdempotencyKey> existing = idempotentService.tryInsert(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, requestHash);

        if (existing.isPresent()) {
            IdempotencyKey e = existing.get();

            if (!e.getRequestHash().equals(requestHash)) {
                return errorResult(409, "Request Hash Mismatch");
            }

            if (e.getStatus() == IdempotentStatus.PROCESSING) {
                return errorResult(409, "Processing confirm");
            }

            return new PaymentApiResult(e.getResponseCode(), e.getResponseBody());
        }


        FdsRequestContext context = paymentCommandService.createFdsRequest(paymentKey);
        FdsCheckResponse fdsResponse = fdsClient.check(new FdsCheckRequest(
                context.paymentKey(),
                context.orderId(),
                context.merchantId(),
                context.amount()
        ));

        if (!fdsResponse.success()) {
            PaymentResponse response = paymentCommandService.failFds(
                    context.paymentKey(),
                    context.transactionId(),
                    fdsResponse
            );
            String responseBody = objectMapper.writeValueAsString(response);
            idempotentService.complete(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, 200, responseBody);
            return new PaymentApiResult(200, responseBody);
        }

        CaptureRequestContext captureContext = paymentCommandService.completeFdsAndCreateCaptureRequest(
                context.paymentKey(),
                context.transactionId(),
                fdsResponse
        );

        String captureIdempotentKey = captureContext.paymentKey() + ":capture";
        CardCaptureResponse captureResponse = cardClient.capture(
                captureContext.authorizationId(),
                new CardCaptureRequest(
                        captureIdempotentKey,
                        captureContext.orderId(),
                        captureContext.amount()
                )
        );

        PaymentResponse response = paymentCommandService.completeCapture(
                captureContext.paymentKey(),
                captureContext.transactionId(),
                captureResponse
        );

        String responseBody = objectMapper.writeValueAsString(response);
        idempotentService.complete(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, 200, responseBody);

        return new PaymentApiResult(200, responseBody);
    }

    private PaymentApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new PaymentApiResult(statusCode, responseBody);
    }
}
