package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.client.card.CardRefundRequest;
import com.example.paymentsystem.payment.client.card.CardRefundResponse;
import com.example.paymentsystem.payment.domain.IdempotencyKey;
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
        String idempotentKey = request.paymentKey() + ":refund:" + request.refundKey();
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
                refundContext.refundKey(),
                refundContext.cardRequestRef(),
                refundContext.amount()
        );

        return externalCallExecutor.execute(
                () -> cardClient.refund(refundContext.cardCompany(), refundContext.captureId(), cardRefundRequest),
                response -> handleRefundResponse(idempotentKey, refundContext, response),
                () -> completeRequest(
                        idempotentKey,
                        IdempotencyOperation.PAYMENT_REFUND,
                        refundCommandService.unknownRefund(refundContext.transactionId(), refundContext.refundKey())
                ),
                () -> completeRequest(
                        idempotentKey,
                        IdempotencyOperation.PAYMENT_REFUND,
                        refundCommandService.failRefund(refundContext.transactionId(), refundContext.refundKey(), null)
                )
        );
    }

    private PaymentApiResult handleRefundResponse(
            String idempotentKey,
            RefundRequestContext context,
            CardRefundResponse response
    ) {
        RefundResponse refundResponse = response.success()
                ? refundCommandService.completeRefund(context.transactionId(), context.refundKey(), context.amount(), response.externalId(), LedgerSourceType.REFUND_TRANSACTION)
                : refundCommandService.failRefund(context.transactionId(), context.refundKey(), response.externalId());

        return completeRequest(idempotentKey, IdempotencyOperation.PAYMENT_REFUND, refundResponse);
    }

    private PaymentApiResult completeRequest(String idempotentKey, IdempotencyOperation operation, RefundResponse response) {
        String responseBody = objectMapper.writeValueAsString(response);
        idempotentService.complete(idempotentKey, operation, 200, responseBody);
        return new PaymentApiResult(200, responseBody);
    }

    private PaymentApiResult completeError(String idempotentKey, IdempotencyOperation operation, int statusCode, String message) {
        String responseBody = objectMapper.writeValueAsString(new ErrorResponse(message));
        idempotentService.complete(idempotentKey, operation, statusCode, responseBody);
        return new PaymentApiResult(statusCode, responseBody);
    }
}
