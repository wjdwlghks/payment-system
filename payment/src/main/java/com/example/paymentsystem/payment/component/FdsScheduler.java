package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.service.FdsExecutionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FdsScheduler {

    private final FdsExecutionService fdsExecutionService;

    @Scheduled(fixedDelay = 10_000)
    public void checkAuthReadyPayment() {
        List<PaymentIntent> paymentIntents = fdsExecutionService.getAuthReadyPaymentIntents();

        for (PaymentIntent paymentIntent : paymentIntents) {
            try {
                fdsExecutionService.checkFds(paymentIntent);
            } catch (Exception e) {
                log.error("Failed to run FDS for auth-ready payment. paymentIntentId={}", paymentIntent.getId(), e);
            }
        }
    }
}
