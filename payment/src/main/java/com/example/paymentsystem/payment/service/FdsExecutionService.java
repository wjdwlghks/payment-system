package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.fds.FdsCheckRequest;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.dto.FdsRequestContext;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovery path: continues FDS for intents stranded at AUTH_READY
 * (auth resolved via inquiry but the request-phase FDS check never ran).
 * Mirrors how the capture step used to be continued for FDS_READY intents.
 */
@Service
@RequiredArgsConstructor
public class FdsExecutionService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentCommandService paymentCommandService;
    private final ExternalCallExecutor externalCallExecutor;
    private final FdsClient fdsClient;

    @Transactional(readOnly = true)
    public List<PaymentIntent> getAuthReadyPaymentIntents() {
        return paymentIntentRepository.findTop30ByStatusOrderByUpdatedAtAsc(PaymentIntentStatus.AUTH_READY);
    }

    public void checkFds(PaymentIntent paymentIntent) {
        FdsRequestContext fdsContext = paymentCommandService.createFdsRequest(paymentIntent.getPaymentKey());

        FdsCheckRequest checkRequest = new FdsCheckRequest(
                fdsContext.idempotentKey(),
                fdsContext.paymentKey(),
                fdsContext.orderId(),
                fdsContext.merchantId(),
                fdsContext.amount()
        );

        externalCallExecutor.execute(
                () -> fdsClient.check(checkRequest),
                response -> response.success()
                        ? paymentCommandService.completeFds(fdsContext.transactionId(), response.externalId())
                        : paymentCommandService.failFds(fdsContext.transactionId(), response.externalId()),
                () -> paymentCommandService.unknownFds(fdsContext.transactionId()),
                () -> paymentCommandService.failFds(fdsContext.transactionId(), null)
        );
    }
}
