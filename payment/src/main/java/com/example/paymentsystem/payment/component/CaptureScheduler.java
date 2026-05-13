package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.service.CaptureExecutionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureScheduler {

    private final CaptureExecutionService captureExecutionService;

    @Scheduled(fixedDelay = 10_000)
    public void captureFdsReadyPayment() {
        List<PaymentIntent> paymentIntents = captureExecutionService.getFdsReadyPaymentIntents();

        for (PaymentIntent paymentIntent : paymentIntents) {
            try {
                captureExecutionService.capture(paymentIntent);
            } catch (Exception e) {
                log.error("Failed to capture fds-ready payment. paymentIntentId={}", paymentIntent.getId(), e);
            }
        }
    }
}
