package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.service.ExpiryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code FDS_PASSED}에 고인 결제를 만료시킨다.
 *
 * <p>다른 스케줄러들과 달리 이건 <b>안전망이 아니라 정상 경로</b>다. "가맹점이 승인을
 * 안 불렀다"를 알려주는 이벤트가 없어서, {@code inquiryStaleRequested}와 마찬가지로
 * 주기 스캔 말고는 발견할 방법이 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiryScheduler {

    private final ExpiryService expiryService;

    @Scheduled(fixedDelayString = "${payment.expiry.scan-interval-ms:60000}")
    public void expireStaleFdsPassed() {
        List<Long> targets = expiryService.findExpirableIds();
        if (targets.isEmpty()) {
            return;
        }

        int expired = 0;
        for (Long id : targets) {
            try {
                if (expiryService.expire(id)) {
                    expired++;
                }
            } catch (Exception e) {
                log.error("Failed to expire payment intent. paymentIntentId={}", id, e);
            }
        }
        log.info("Expired {} payment intents stranded at FDS_PASSED.", expired);
    }
}
