package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardAuthRequest;
import com.example.paymentsystem.payment.client.card.CardAuthResponse;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.client.fds.FdsCheckRequest;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
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
    private final ExternalCallExecutor externalCallExecutor;
    private final CaptureExecutionService captureExecutionService;
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

        AuthRequestContext authContext = paymentCommandService.createAuthRequest(request);

        CardAuthRequest authRequest = new CardAuthRequest(
                authContext.idempotentKey(),
                authContext.cardRequestRef(),
                authContext.orderId(),
                authContext.merchantId(),
                authContext.amount()
        );

        return externalCallExecutor.execute(
                () -> cardClient.authorize(authRequest),
                response -> handleAuthResponse(idempotentKey, authContext, response),
                () -> completeRequest(
                        idempotentKey,
                        IdempotencyOperation.PAYMENT_REQUEST,
                        paymentCommandService.unknownAuth(authContext.transactionId())
                ),
                () -> completeRequest(
                        idempotentKey,
                        IdempotencyOperation.PAYMENT_REQUEST,
                        paymentCommandService.failAuth(authContext.transactionId(), null)
                )
        );
    }

    public PaymentApiResult confirmPayment(String paymentKey) {

        PaymentIntent paymentIntent = paymentCommandService.getPaymentIntent(paymentKey);

        if (paymentIntent.getStatus() != PaymentIntentStatus.FDS_READY) {
            return errorResult(422, "Payment not confirmable: status is " + paymentIntent.getStatus());
        }

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

        CaptureRequestContext captureContext = paymentCommandService.createCaptureRequest(paymentKey);
        PaymentResponse paymentResponse = captureExecutionService.captureWithRetry(captureContext);
        return completeRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, paymentResponse);
    }

    private PaymentApiResult handleAuthResponse(
            String idempotentKey,
            AuthRequestContext context,
            CardAuthResponse response
    ) {
        if (!response.success()) {
            return completeRequest(
                    idempotentKey,
                    IdempotencyOperation.PAYMENT_REQUEST,
                    paymentCommandService.failAuth(context.transactionId(), response.externalId())
            );
        }

        paymentCommandService.completeAuth(context.transactionId(), response.externalId(), response.authorizedAt());
        return runFdsCheck(idempotentKey, context.paymentKey());
    }

    private PaymentApiResult runFdsCheck(String idempotentKey, String paymentKey) {
        FdsRequestContext fdsContext = paymentCommandService.createFdsRequest(paymentKey);

        FdsCheckRequest checkRequest = new FdsCheckRequest(
                fdsContext.idempotentKey(),
                fdsContext.paymentKey(),
                fdsContext.orderId(),
                fdsContext.merchantId(),
                fdsContext.amount()
        );

        return externalCallExecutor.execute(
                () -> fdsClient.check(checkRequest),
                response -> handleFdsResponse(idempotentKey, fdsContext, response),
                () -> completeRequest(
                        idempotentKey,
                        IdempotencyOperation.PAYMENT_REQUEST,
                        paymentCommandService.unknownFds(fdsContext.transactionId())
                ),
                () -> completeRequest(
                        idempotentKey,
                        IdempotencyOperation.PAYMENT_REQUEST,
                        paymentCommandService.failFds(fdsContext.transactionId(), null)
                )
        );
    }

    private PaymentApiResult handleFdsResponse(
            String idempotentKey,
            FdsRequestContext context,
            FdsCheckResponse response
    ) {
        PaymentResponse paymentResponse = response.success()
                ? paymentCommandService.completeFds(context.transactionId(), response.externalId())
                : paymentCommandService.failFds(context.transactionId(), response.externalId());

        return completeRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, paymentResponse);
    }

    private PaymentApiResult completeRequest(
            String idempotentKey,
            IdempotencyOperation operation,
            PaymentResponse paymentResponse
    ) {
        String responseBody = objectMapper.writeValueAsString(paymentResponse);
        idempotentService.complete(idempotentKey, operation, 200, responseBody);
        return new PaymentApiResult(200, responseBody);
    }

    private PaymentApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new PaymentApiResult(statusCode, responseBody);
    }
}
