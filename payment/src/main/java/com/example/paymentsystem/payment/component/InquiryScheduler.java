package com.example.paymentsystem.payment.component;


import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.service.IdempotentRecoveryService;
import com.example.paymentsystem.payment.service.InquiryService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 복구 경로의 안전망.
 *
 * <p>UNKNOWN 재조회의 정상 경로는 {@link InquiryQueue}다 — UNKNOWN이 확정되면 이벤트로 예약되고
 * 정확히 그 시각에 깨어난다. 여기 스캔이 남아 있는 이유는 그 큐가 힙에 있기 때문이다.
 * 프로세스가 죽거나 적재가 실패하면 예약이 사라지는데, {@code next_inquiry_at}은 DB에 남아 있으므로
 * <b>due를 한참 넘긴 행 = 큐가 잃어버린 행</b>으로 판정해 다시 넣을 수 있다.
 *
 * <p>REQUESTED 방치는 사정이 다르다. "요청한 지 오래됐다"를 알려주는 이벤트가 없어서
 * 스캔 외에는 찾을 방법이 없다. 그래서 그쪽은 여전히 주기 스캔이 정상 경로다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InquiryScheduler {

    /**
     * 이만큼 due를 넘긴 건만 "큐가 놓쳤다"고 본다.
     *
     * <p>짧게 잡으면 큐가 정상적으로 처리하려는 건을 sweeper가 가로채 중복 조회한다.
     * 소비자가 조회 직전에 다음 시각을 미리 적어두므로, 정상 동작 중이라면 due가 지난 행은
     * 존재하지 않는다 — 30초는 그 전제가 잠깐 어긋나는 구간에 대한 여유다.
     */
    private static final Duration LOST_GRACE = Duration.ofSeconds(30);

    private static final int SWEEP_LIMIT = 1_000;

    private final InquiryQueue inquiryQueue;
    private final InquiryService inquiryService;
    private final IdempotentRecoveryService idempotentRecoveryService;

    /** 큐가 잃어버린 UNKNOWN을 회수한다. 정상 동작 중에는 아무것도 안 잡히는 게 맞다. */
    @Scheduled(fixedDelayString = "${payment.inquiry.sweep-interval-ms:60000}")
    public void sweepLostUnknowns() {
        List<Long> lost = inquiryService.findOverdueUnknownIds(LOST_GRACE, SWEEP_LIMIT);
        if (lost.isEmpty()) {
            return;
        }
        log.warn("Re-queueing {} UNKNOWN transactions the in-memory queue lost.", lost.size());
        lost.forEach(id -> inquiryQueue.schedule(id, Duration.ZERO));
    }

    /** 크래시로 REQUESTED에 방치된 건. 이벤트가 없어 스캔이 유일한 발견 수단이다. */
    @Scheduled(fixedDelay = 30_000)
    public void inquiryStaleRequested() {
        List<Long> stale = inquiryService.findStaleRequestedIds(SWEEP_LIMIT);
        stale.forEach(id -> inquiryQueue.schedule(id, Duration.ZERO));
    }

    @Scheduled(fixedDelay = 30_000)
    public void recoverStaleIdempotencyKeys() {
        List<IdempotencyKey> stale = idempotentRecoveryService.getStaleProcessing();

        for (IdempotencyKey key : stale) {
            try {
                idempotentRecoveryService.recover(key);
            } catch (Exception e) {
                log.error("Failed to recover idempotency key. id={}", key.getId(), e);
            }
        }
    }
}
