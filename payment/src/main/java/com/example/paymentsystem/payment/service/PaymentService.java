package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardAuthRequest;
import com.example.paymentsystem.payment.client.card.CardAuthResponse;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.client.fds.FdsCheckRequest;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotentKeys;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.dto.*;
import com.example.paymentsystem.payment.exception.PaymentValidationException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentCommandService paymentCommandService;
    private final IdempotentService idempotentService;
    private final ExternalCallExecutor externalCallExecutor;
    private final ApproveExecutionService approveExecutionService;
    private final CaptureCommandService captureCommandService;
    private final CaptureExecutionService captureExecutionService;
    private final CancelService cancelService;
    private final CardClient cardClient;
    private final FdsClient fdsClient;
    private final ObjectMapper objectMapper;

    public PaymentApiResult authenticationPayment(PaymentRequest request) {

        String idempotentKey = IdempotentKeys.paymentRequest(request.merchantId(), request.orderId());
        String requestHash = DigestUtils.sha256Hex(
                request.merchantId() + ":" + request.orderId() + ":" + request.amount()
        );

        AuthRequestContext authContext;
        try {
            authContext = paymentCommandService.createAuthRequestWithIdempotency(request, idempotentKey, requestHash);
        } catch (DataIntegrityViolationException e) {
            return replayOrReject(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, requestHash, "Processing Request");
        }

        CardAuthRequest authRequest = new CardAuthRequest(
                authContext.cardRequestRef(),
                authContext.orderId(),
                authContext.merchantId()
        );

        return externalCallExecutor.execute(
                () -> cardClient.authenticate(request.cardCompany(), authRequest),
                response -> handleAuthResponse(idempotentKey, authContext, response),
                // UNKNOWN은 확정된 결과가 아니므로 멱등키를 완결하지 않는다 — PROCESSING을 유지한 채
                // InquiryScheduler가 실제 상태를 확정하는 시점에 그 트랜잭션에서 완결된다.
                () -> toApiResult(paymentCommandService.unknownAuth(authContext.transactionId())),
                () -> paymentCommandService.failAuth(authContext.transactionId(), null, idempotentKey)
        );
    }

    public PaymentApiResult approvePayment(String paymentKey) {

        ApproveRequestContext approveContext;
        try {
            approveContext = paymentCommandService.createApproveRequestWithIdempotency(paymentKey);
        } catch (PaymentValidationException e) {
            return errorResult(e.getStatusCode(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            PaymentIntent paymentIntent = paymentCommandService.getPaymentIntent(paymentKey);
            String existingKey = IdempotentKeys.paymentApprove(paymentIntent.getMerchantId(), paymentKey);
            return replayOrReject(existingKey, IdempotencyOperation.PAYMENT_APPROVE, null, "Processing approve");
        }

        String idempotentKey = IdempotentKeys.paymentApprove(approveContext.merchantId(), approveContext.paymentKey());
        return approveExecutionService.approve(approveContext, idempotentKey);
    }

    public PaymentApiResult capturePayment(String paymentKey) {

        CaptureRequestContext captureContext;
        try {
            captureContext = captureCommandService.createCaptureRequestWithIdempotency(paymentKey);
        } catch (PaymentValidationException e) {
            return errorResult(e.getStatusCode(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            PaymentIntent paymentIntent = paymentCommandService.getPaymentIntent(paymentKey);
            String existingKey = IdempotentKeys.paymentCapture(paymentIntent.getMerchantId(), paymentKey);
            return replayOrReject(existingKey, IdempotencyOperation.PAYMENT_CAPTURE, null, "Processing capture");
        }

        return captureExecutionService.capture(captureContext);
    }

    /**
     * 승인취소. 매입 전 승인건만 대상이고, 매입이 확정된 뒤에는 환불의 영역이라 거부한다.
     *
     * <p>다른 단계와 달리 UNKNOWN 분기가 없다 — {@code CancelService}가 카드사 호출까지
     * 한 트랜잭션에 넣고 확정 응답만 인정하므로, 여기서는 성공 아니면 롤백뿐이다.
     */
    public PaymentApiResult cancelPayment(String paymentKey) {
        try {
            return toApiResult(cancelService.cancel(paymentKey));
        } catch (PaymentValidationException e) {
            return errorResult(e.getStatusCode(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            PaymentIntent paymentIntent = paymentCommandService.getPaymentIntent(paymentKey);
            String existingKey = IdempotentKeys.paymentCancel(paymentIntent.getMerchantId(), paymentKey);
            return replayOrReject(existingKey, IdempotencyOperation.PAYMENT_CANCEL, null, "Processing cancel");
        }
    }

    private PaymentApiResult handleAuthResponse(
            String idempotentKey,
            AuthRequestContext context,
            CardAuthResponse response
    ) {
        if (!response.success()) {
            return paymentCommandService.failAuth(context.transactionId(), response.externalId(), idempotentKey);
        }

        FdsRequestContext fdsContext = paymentCommandService.completeAuthAndRequestFds(
                context.transactionId(), response.externalId(), response.authenticatedAt()
        );
        return runFdsCheck(idempotentKey, fdsContext);
    }

    private PaymentApiResult runFdsCheck(String idempotentKey, FdsRequestContext fdsContext) {
        FdsCheckRequest checkRequest = new FdsCheckRequest(
                fdsContext.cardRequestRef(),
                fdsContext.paymentKey(),
                fdsContext.orderId(),
                fdsContext.merchantId(),
                fdsContext.amount()
        );

        return externalCallExecutor.execute(
                () -> fdsClient.check(checkRequest),
                response -> handleFdsResponse(idempotentKey, fdsContext, response),
                () -> toApiResult(paymentCommandService.unknownFds(fdsContext.transactionId())),
                () -> paymentCommandService.failFds(fdsContext.transactionId(), null, idempotentKey)
        );
    }

    private PaymentApiResult handleFdsResponse(
            String idempotentKey,
            FdsRequestContext context,
            FdsCheckResponse response
    ) {
        return response.success()
                ? paymentCommandService.completeFds(context.transactionId(), response.externalId(), idempotentKey)
                : paymentCommandService.failFds(context.transactionId(), response.externalId(), idempotentKey);
    }

    // 병합 트랜잭션이 유니크 제약 위반으로 롤백된 뒤, 기존 idempotency key를 조회해 재생/거절을 결정한다.
    private PaymentApiResult replayOrReject(String idempotentKey, IdempotencyOperation operation, String requestHash, String processingMessage) {
        IdempotencyKey existing = idempotentService.find(idempotentKey, operation)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key not found after constraint violation: " + idempotentKey));

        // requestHash가 없는 operation(승인)은 키 자체가 요청을 규정하므로 지문 비교를 건너뛴다.
        if (requestHash != null && !requestHash.equals(existing.getRequestHash())) {
            return errorResult(409, "Request Hash Mismatch");
        }

        if (existing.getStatus() == IdempotentStatus.PROCESSING) {
            return errorResult(409, processingMessage);
        }

        return new PaymentApiResult(existing.getResponseCode(), existing.getResponseBody());
    }

    private PaymentApiResult toApiResult(PaymentResponse response) {
        return new PaymentApiResult(200, objectMapper.writeValueAsString(response));
    }

    private PaymentApiResult errorResult(int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        return new PaymentApiResult(statusCode, responseBody);
    }
}
