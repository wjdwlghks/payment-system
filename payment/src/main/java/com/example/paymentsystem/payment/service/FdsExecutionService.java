package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.fds.FdsCheckRequest;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.domain.IdempotentKeys;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.dto.FdsRequestContext;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovery path: continues FDS for intents stranded at AUTHENTICATED
 * (auth resolved via inquiry but the request-phase FDS check never ran).
 * Mirrors how the capture step used to be continued for FDS_PASSED intents.
 *
 * <p>이 경로가 FDS를 종료 상태로 만들면 request phase가 끝나므로, 그 트랜잭션에서
 * PAYMENT_REQUEST 멱등키까지 함께 완결한다.
 */
@Service
@RequiredArgsConstructor
public class FdsExecutionService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final FdsClient fdsClient;

    @Transactional(readOnly = true)
    public List<PaymentIntent> getAuthenticatedPaymentIntents() {
        return paymentIntentRepository.findTop300ByStatusOrderByUpdatedAtAsc(PaymentIntentStatus.AUTHENTICATED);
    }

    public void checkFds(PaymentIntent paymentIntent) {
        FdsRequestContext fdsContext = paymentCommandService.createFdsRequest(paymentIntent.getPaymentKey());

        FdsCheckRequest checkRequest = new FdsCheckRequest(
                fdsContext.cardRequestRef(),
                fdsContext.paymentKey(),
                fdsContext.orderId(),
                fdsContext.merchantId(),
                fdsContext.amount()
        );

        String idempotentKey = IdempotentKeys.paymentRequest(fdsContext.merchantId(), fdsContext.orderId());

        externalCallExecutor.execute(
                () -> fdsClient.check(checkRequest),
                response -> response.success()
                        ? paymentCommandService.completeFdsAndComplete(fdsContext.transactionId(), response.externalId(), idempotentKey)
                        : paymentCommandService.failFdsAndComplete(fdsContext.transactionId(), response.externalId(), idempotentKey),
                // UNKNOWN은 확정이 아니므로 멱등키를 완결하지 않는다 — InquiryScheduler가 이어받는다.
                () -> paymentCommandService.unknownFds(fdsContext.transactionId()),
                () -> paymentCommandService.failFdsAndComplete(fdsContext.transactionId(), null, idempotentKey)
        );
    }
}
