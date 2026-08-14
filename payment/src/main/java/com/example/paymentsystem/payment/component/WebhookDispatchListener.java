package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.service.WebhookQueued;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 상태 전이 트랜잭션이 커밋되는 즉시 웹훅을 배달한다.
 *
 * <p>기존에는 모든 웹훅이 {@link WebhookScheduler}의 틱을 기다렸다. 틱당 300건 / 3초라
 * <b>초당 100건</b>이 상한인데, 150 TPS 부하에서 실측한 생성량은 초당 305건이었다.
 * 3배 부족해서 백로그가 계속 자랐고, 결제는 이미 확정됐는데 가맹점이 몇 분씩 몰랐다.
 *
 * <p>여기서 즉시 배달하면 정상 경로가 스케줄러를 아예 거치지 않으므로 그 상한이 사라진다.
 * 스케줄러는 <b>이 즉시 배달이 실패했거나 아예 못 돈 건</b>만 줍는 안전망으로 내려간다.
 *
 * <p><b>아웃박스는 그대로 유지된다.</b> 이 리스너는 인메모리라 커밋 직후 JVM이 죽으면
 * 그대로 사라진다 — 행이 DB에 남아 있어야 복구가 가능하다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class WebhookDispatchListener {

    /**
     * 커밋한 스레드를 붙잡지 않기 위한 전용 풀.
     *
     * <p>{@code @Async}를 쓰지 않고 executor를 직접 부른다. 프록시 기반이 아니라 자기 호출
     * 함정이 없고, merchant의 {@code PaymentFlow}와 같은 방식이라 일관된다.
     *
     * <p>큐를 <b>바운드</b>로 두는 게 핵심이다. 무제한 큐면 merchant가 느려졌을 때
     * 배달 대기가 JVM 메모리 안에 보이지 않게 쌓이고 재시작하면 통째로 사라진다 —
     * 지금의 DB 백로그보다 나쁘다. 넘치면 거절하고 행은 PENDING으로 남겨
     * 원래의 안전망(스케줄러)이 가져가게 한다.
     */
    private static final int WORKERS = 64;
    private static final int QUEUE_CAPACITY = 5_000;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            WORKERS, WORKERS,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r);
                t.setName("webhook-dispatch-" + t.threadId());
                t.setDaemon(true);
                return t;
            }
    );

    private final WebhookDispatcher dispatcher;

    /**
     * AFTER_COMMIT이라 아웃박스 행이 이미 커밋된 뒤에 실행된다 — 다른 스레드에서 읽어도 보인다.
     *
     * <p>이 메서드는 <b>제출만</b> 하고 즉시 반환해야 한다. 리스너는 기본적으로 커밋한 스레드에서
     * 동기 실행되는데, 그 스레드가 가맹점 API의 Tomcat 스레드이거나 UNKNOWN을 복구 중인
     * inquiry 스케줄러 스레드다. 여기서 HTTP를 치면 결제 응답시간에 웹훅 왕복이 얹히고,
     * 300건짜리 순차 복구 루프가 건당 왕복만큼 늘어진다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWebhookQueued(WebhookQueued event) {
        try {
            executor.execute(() -> dispatcher.deliverIfPending(event.outboxId()));
        } catch (RejectedExecutionException e) {
            // 손실이 아니다 — 행은 PENDING으로 남아 유예 시간 뒤 스케줄러가 가져간다.
            log.warn("Webhook dispatch queue full; falling back to the scheduler. outboxId={}",
                    event.outboxId());
        }
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
