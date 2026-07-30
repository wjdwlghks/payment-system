package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.client.card.CardRefundRequest;
import com.example.paymentsystem.payment.client.card.CardRefundResponse;
import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotentKeys;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.dto.*;
import com.example.paymentsystem.payment.exception.RefundValidationException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final IdempotentService idempotentService;
    private final RefundCommandService refundCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;
    private final ObjectMapper objectMapper;


    public PaymentApiResult refund(RefundRequest request) {
        String idempotentKey = IdempotentKeys.paymentRefund(request.paymentKey(), request.refundKey());
        String requestHash = DigestUtils.sha256Hex(
                request.paymentKey() + ":" + request.amount() + ":" + request.refundKey()
        );

        Optional<IdempotencyKey> existing = idempotentService.tryInsert(idempotentKey, IdempotencyOperation.PAYMENT_REFUND, requestHash);

        if (existing.isPresent()) {
            IdempotencyKey e = existing.get();

            if (!e.getRequestHash().equals(requestHash)) {
                throw new RuntimeException("Request Hash Mismatch");
            }

            if (e.getStatus() == IdempotentStatus.PROCESSING) {
                throw new RuntimeException("Refund is Processing");
            }

            return new PaymentApiResult(e.getResponseCode(), e.getResponseBody());
        }

        RefundRequestContext refundContext;
        try {
            refundContext = refundCommandService.createRefundRequest(request);
        } catch (RefundValidationException e) {
            return completeError(idempotentKey, IdempotencyOperation.PAYMENT_REFUND, e.getStatusCode(), e.getMessage());
        }

        CardRefundRequest cardRefundRequest = new CardRefundRequest(
                refundContext.cardRequestRef(),
                refundContext.amount()
        );

        return externalCallExecutor.execute(
                () -> cardClient.refund(refundContext.cardCompany(), refundContext.captureId(), cardRefundRequest),
                response -> handleRefundResponse(idempotentKey, refundContext, response),
                // UNKNOWN은 확정된 결과가 아니므로 멱등키를 완결하지 않는다 — PROCESSING을 유지한 채
                // InquiryScheduler가 실제 상태를 확정하는 시점에 그 트랜잭션에서 완결된다.
                () -> toApiResult(refundCommandService.unknownRefund(refundContext.transactionId(), refundContext.refundKey())),
                () -> refundCommandService.failRefundAndComplete(
                        refundContext.transactionId(), refundContext.refundKey(), null, idempotentKey
                )
        );
    }

    private PaymentApiResult handleRefundResponse(
            String idempotentKey,
            RefundRequestContext context,
            CardRefundResponse response
    ) {
        return response.success()
                ? refundCommandService.completeRefundAndComplete(
                        context.transactionId(), context.refundKey(), context.amount(),
                        response.externalId(), idempotentKey, LedgerSourceType.REFUND_TRANSACTION)
                : refundCommandService.failRefundAndComplete(
                        context.transactionId(), context.refundKey(), response.externalId(), idempotentKey);
    }

    private PaymentApiResult toApiResult(RefundResponse response) {
        return new PaymentApiResult(200, objectMapper.writeValueAsString(response));
    }

    private PaymentApiResult completeError(String idempotentKey, IdempotencyOperation operation, int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        idempotentService.complete(idempotentKey, operation, statusCode, responseBody);
        return new PaymentApiResult(statusCode, responseBody);
    }
}
