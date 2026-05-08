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
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
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

        AuthRequestContext authContext = paymentCommandService.createAuthRequest(request);
        String authIdempotentKey = authContext.paymentKey() + ":auth";

        CardAuthRequest authRequest = new CardAuthRequest(
                authIdempotentKey,
                authContext.orderId(),
                authContext.merchantId(),
                authContext.amount()
        );

        try {
            CardAuthResponse authResponse = cardClient.authorize(authRequest);
            if (!authResponse.success()) {
                return failAuth(idempotentKey, authContext, authResponse);
            }

            return completeAuth(idempotentKey, authContext, authResponse);

        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                return unknownAuth(idempotentKey, authContext);
            }

            return failAuth(idempotentKey, authContext);
        } catch (RestClientResponseException e) {
            return failAuth(idempotentKey, authContext);
        }
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


        FdsRequestContext fdsContext = paymentCommandService.createFdsRequest(paymentKey);

        FdsCheckRequest checkRequest = new FdsCheckRequest(
                fdsContext.paymentKey(),
                fdsContext.orderId(),
                fdsContext.merchantId(),
                fdsContext.amount()
        );

        try {
            FdsCheckResponse fdsResponse = fdsClient.check(checkRequest);
            if (!fdsResponse.success()) {
                return failFds(idempotentKey, fdsContext, fdsResponse);
            }
            return capture(idempotentKey, fdsContext, fdsResponse);

        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                return unknownFds(idempotentKey, fdsContext);
            }

            return failFds(idempotentKey, fdsContext);

        } catch (RestClientResponseException e) {
            return failFds(idempotentKey, fdsContext);
        }
    }

    private PaymentApiResult capture(String idempotentKey, FdsRequestContext fdsContext, FdsCheckResponse fdsResponse) {
        CaptureRequestContext captureContext = paymentCommandService.completeFdsAndCreateCaptureRequest(
                fdsContext.paymentKey(),
                fdsContext.transactionId(),
                fdsResponse
        );

        String captureIdempotentKey = fdsContext.paymentKey() + ":capture";
        CardCaptureRequest captureRequest = new CardCaptureRequest(
                captureIdempotentKey,
                captureContext.orderId(),
                captureContext.amount()
        );

        try {
            CardCaptureResponse captureResponse = cardClient.capture(captureContext.authorizationId(), captureRequest);
            if (!captureResponse.success()) {
                return failCapture(idempotentKey, captureContext, captureResponse);
            }
            return completeCapture(idempotentKey, captureContext, captureResponse);

        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                return unknownCapture(idempotentKey, captureContext);
            }

            return failCapture(idempotentKey, captureContext);

        } catch (RestClientResponseException e) {
            return failCapture(idempotentKey, captureContext);
        }
    }

    private PaymentApiResult completeCapture(String idempotentKey, CaptureRequestContext context, CardCaptureResponse captureResponse) {
        PaymentResponse response = paymentCommandService.completeCapture(context.paymentKey(), context.transactionId(), captureResponse);
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult completeAuth(String idempotentKey, AuthRequestContext context, CardAuthResponse cardResponse) {
        PaymentResponse response = paymentCommandService.completeAuth(context.paymentKey(), context.transactionId(), cardResponse);
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, response);
    }

    private PaymentApiResult failAuth(String idempotentKey, AuthRequestContext context) {
        PaymentResponse response = paymentCommandService.failAuth(context.paymentKey(), context.transactionId());
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, response);
    }

    private PaymentApiResult failAuth(String idempotentKey, AuthRequestContext context, CardAuthResponse cardResponse) {
        PaymentResponse response = paymentCommandService.failAuth(context.paymentKey(), context.transactionId(), cardResponse);
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, response);
    }

    private PaymentApiResult failFds(String idempotentKey, FdsRequestContext context) {
        PaymentResponse response = paymentCommandService.failFds(context.paymentKey(), context.transactionId());
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult failFds(String idempotentKey, FdsRequestContext context, FdsCheckResponse fdsResponse) {
        PaymentResponse response = paymentCommandService.failFds(context.paymentKey(), context.transactionId(), fdsResponse);
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult failCapture(String idempotentKey, CaptureRequestContext context, CardCaptureResponse captureResponse) {
        PaymentResponse response = paymentCommandService.failCapture(context.paymentKey(), context.transactionId(), captureResponse);
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult failCapture(String idempotentKey, CaptureRequestContext context) {
        PaymentResponse response = paymentCommandService.failCapture(context.paymentKey(), context.transactionId());
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult unknownAuth(String idempotentKey, AuthRequestContext context) {
        PaymentResponse response = paymentCommandService.unknownAuth(context.paymentKey(), context.transactionId());
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, response);
    }

    private PaymentApiResult unknownFds(String idempotentKey, FdsRequestContext context) {
         PaymentResponse response = paymentCommandService.unknownFds(context.paymentKey(), context.transactionId());
         return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult unknownCapture(String idempotentKey, CaptureRequestContext context) {
        PaymentResponse response = paymentCommandService.unknownCapture(context.paymentKey(), context.transactionId());
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, response);
    }

    private PaymentApiResult completeIdempotentRequest(
            String idempotentKey,
            IdempotencyOperation operation,
            PaymentResponse paymentResponse
    ) {
        String responseBody = objectMapper.writeValueAsString(paymentResponse);
        idempotentService.complete(idempotentKey, operation, 200, responseBody);
        return new PaymentApiResult(200, responseBody);
    }

    private boolean isReadTimeout(Throwable throwable) {
        return isCausedBy(throwable, SocketTimeoutException.class)
                && !isConnectTimeout(throwable);
    }

    private boolean isConnectTimeout(Throwable throwable) {
        return isCausedBy(throwable, ConnectTimeoutException.class);
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private PaymentApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new PaymentApiResult(statusCode, responseBody);
    }
}
