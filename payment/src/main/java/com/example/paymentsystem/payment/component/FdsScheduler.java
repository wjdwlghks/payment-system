package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.service.FdsExecutionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AUTHENTICATED에 멈춰 있는 intent의 안전망.
 *
 * <p>정상적으로는 여기 걸릴 게 없다 — 동기 흐름은 {@code completeAuthAndRequestFds}로,
 * inquiry 복구 흐름은 AUTH 확정 직후 인라인 FDS 호출로 각각 FDS까지 진행시킨다.
 * 그 인라인 호출이 실패한 건만 여기로 떨어지므로, 이 주기는 사용자 대기시간에 얹히지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FdsScheduler {

    private final FdsExecutionService fdsExecutionService;

    @Scheduled(fixedDelay = 10_000)
    public void checkAuthenticatedPayment() {
        List<PaymentIntent> paymentIntents = fdsExecutionService.getAuthenticatedPaymentIntents();

        for (PaymentIntent paymentIntent : paymentIntents) {
            try {
                fdsExecutionService.checkFds(paymentIntent);
            } catch (Exception e) {
                log.error("Failed to run FDS for authenticated payment. paymentIntentId={}", paymentIntent.getId(), e);
            }
        }
    }
}
