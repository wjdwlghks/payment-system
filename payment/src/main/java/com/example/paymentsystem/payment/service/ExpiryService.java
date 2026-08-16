package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증까지 끝났는데 승인 요청이 오지 않은 결제를 만료시킨다.
 *
 * <p><b>카드사에 보낼 것이 없다.</b> 인증은 금액을 다루지 않아 한도를 잡지 않으므로
 * ({@code CardAuthentication.amount}는 승인 시점에야 채워진다) 카드사 쪽에 되돌릴 상태가
 * 존재하지 않는다. 그래서 이 경로는 PG 내부 상태 전이와 웹훅이 전부다 —
 * 취소(void)와 달리 외부 호출도, 그에 따른 UNKNOWN도 없다.
 *
 * <p>{@code FDS_PASSED}는 어떤 스캔에도 걸리지 않는 유일한 비종료 상태였다. 트랜잭션이
 * 전부 SUCCEEDED라 조회 대상이 아니고, {@code FdsScheduler}는 {@code AUTHENTICATED}만 본다.
 * 이 서비스가 그 자리를 메운다.
 */
@Service
@RequiredArgsConstructor
public class ExpiryService {

    private static final int SCAN_LIMIT = 300;

    private final PaymentIntentRepository paymentIntentRepository;
    private final WebhookService webhookService;

    /** 승인을 기다려주는 시간. 실제 결제창 세션 수명에 맞춘다. */
    @Value("${payment.expiry.fds-passed-ttl-ms:900000}")
    private long fdsPassedTtlMs;

    @Transactional(readOnly = true)
    public List<Long> findExpirableIds() {
        Instant threshold = Instant.now().minus(Duration.ofMillis(fdsPassedTtlMs));
        return paymentIntentRepository.findExpirableIds(
                PaymentIntentStatus.FDS_PASSED, threshold, Limit.of(SCAN_LIMIT));
    }

    /**
     * 한 건을 만료시킨다. 스캔과 별개 트랜잭션이라 그 사이 승인이 시작됐을 수 있으므로
     * 여기서 상태를 다시 확인한다 — 낙관적 락(@Version)이 나머지 틈을 막는다.
     */
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public boolean expire(Long paymentIntentId) {
        PaymentIntent paymentIntent = paymentIntentRepository.findById(paymentIntentId)
                .orElse(null);
        if (paymentIntent == null || !paymentIntent.isExpirable()) {
            return false;
        }

        paymentIntent.markExpired();
        webhookService.saveExpired(paymentIntent);
        return true;
    }
}
