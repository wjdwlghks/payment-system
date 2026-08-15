package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.dto.PendingInquiry;
import com.example.paymentsystem.payment.service.InquiryService;
import com.example.paymentsystem.payment.service.TransactionUnknown;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@link InquiryQueue}에 일감을 넣는 두 입구.
 *
 * <p>정상 경로는 이벤트다 — UNKNOWN이 확정되는 트랜잭션이 커밋되면 곧바로 예약된다.
 * 다른 하나는 재기동 복원으로, 큐가 힙에 있어 프로세스와 함께 사라지기 때문에 필요하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryQueueLoader {

    /** 복원 시 한 번에 읽을 최대 건수. 넘치면 나머지는 sweeper가 주워간다. */
    private static final int RESTORE_LIMIT = 10_000;

    private final InquiryQueue inquiryQueue;
    private final InquiryService inquiryService;

    /**
     * 커밋 후에 예약한다. 커밋 전에 넣으면 소비자가 다른 스레드에서 아직 안 보이는 행을 읽어
     * "이미 확정됨"으로 오판하고 버릴 수 있다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionUnknown(TransactionUnknown event) {
        inquiryQueue.scheduleInitial(event.transactionId());
    }

    /**
     * 재기동 복원. {@code next_inquiry_at}에 남은 지연을 그대로 살려 넣으므로
     * 백오프 사다리 위치를 잃지 않는다 — 10분까지 밀려 있던 건이 재시작했다고
     * 즉시 재조회되어 죽은 카드사를 다시 때리는 일이 없다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        inquiryQueue.start();

        List<PendingInquiry> pending = inquiryService.findAllPendingInquiries(RESTORE_LIMIT);
        Instant now = Instant.now();
        for (PendingInquiry item : pending) {
            Duration remaining = item.nextInquiryAt() == null
                    ? Duration.ZERO
                    : Duration.between(now, item.nextInquiryAt());
            inquiryQueue.schedule(item.transactionId(), remaining.isNegative() ? Duration.ZERO : remaining);
        }

        if (pending.isEmpty()) {
            log.info("Inquiry queue started; nothing to restore.");
        } else {
            log.info("Inquiry queue started; restored {} pending inquiries.", pending.size());
        }
    }
}
