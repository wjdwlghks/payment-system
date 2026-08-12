package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardApproveRequest;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.dto.ApproveRequestContext;
import com.example.paymentsystem.payment.dto.PaymentApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ApproveExecutionService {

    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final CardClient cardClient;
    private final ObjectMapper objectMapper;

    public PaymentApiResult approveWithRetry(ApproveRequestContext approveContext, String idempotentKey) {
        CardApproveRequest approveRequest = new CardApproveRequest(
                approveContext.cardRequestRef(),
                approveContext.orderId(),
                approveContext.amount()
        );

        return externalCallExecutor.execute(
                () -> cardClient.approve(approveContext.cardCompany(), approveContext.authenticationId(), approveRequest),
                response -> response.success()
                        ? paymentCommandService.completeApproveAndComplete(approveContext.transactionId(), response.externalId(), idempotentKey)
                        : paymentCommandService.failApproveAndComplete(approveContext.transactionId(), response.externalId(), idempotentKey),
                // UNKNOWN은 확정된 결과가 아니므로 멱등키를 완결하지 않는다 — PROCESSING을 유지한 채
                // InquiryScheduler가 실제 상태를 확정하는 시점에 그 트랜잭션에서 완결된다.
                () -> new PaymentApiResult(200, objectMapper.writeValueAsString(
                        paymentCommandService.unknownApprove(approveContext.transactionId()))),
                () -> paymentCommandService.failApproveAndComplete(approveContext.transactionId(), null, idempotentKey)
        );
    }
}
