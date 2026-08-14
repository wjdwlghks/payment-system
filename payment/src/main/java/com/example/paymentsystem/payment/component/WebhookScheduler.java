package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.WebhookOutbox;
import com.example.paymentsystem.payment.service.WebhookService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * 아웃박스 안전망.
 *
 * <p>정상 배달은 {@link WebhookDispatchListener}가 커밋 직후에 끝낸다. 여기로 오는 건
 * 즉시 배달이 실패했거나(가맹점 다운), 큐가 꽉 차 거절됐거나, 커밋 직후 프로세스가 죽어
 * 리스너가 아예 못 돈 건들이다. 그래서 유예 시간({@code DISPATCH_GRACE})이 지난 행만 보인다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookScheduler {

    /**
     * 한 틱이 스레드를 쥘 상한. 아래 drain 루프가 due를 다 비울 때까지 도는데,
     * 가맹점이 오래 죽어 있다 살아나면 수천 건이 한꺼번에 due가 되므로 상한이 필요하다.
     * 초과해도 손실은 없다 — 남은 행은 여전히 PENDING이라 다음 틱이 이어받는다.
     */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(10);

    private final WebhookService webhookService;
    private final WebhookDispatcher dispatcher;

    /**
     * due가 빌 때까지 페이지 단위로 소진한다.
     *
     * <p>기존에는 틱당 한 페이지(300건)만 처리해서 <b>초당 100건</b>이 처리율 상한이었다.
     * 150 TPS 부하의 웹훅 생성량이 초당 305건이었으므로 3배 부족했고, 백로그가 계속 자랐다.
     * 루프로 바꾸면 300은 처리율 상한이 아니라 페이지 크기가 된다.
     *
     * <p>선점(claim) 단계가 따로 없어도 루프가 끝난다 — 배달은 성공하면 SENT로,
     * 실패하면 백오프로 {@code nextAttemptAt}을 밀기 때문에 처리한 행은 어느 쪽이든
     * due 집합에서 빠진다. 다음 페이지는 반드시 서로소다.
     */
    @Scheduled(fixedDelay = 3_000)
    public void webhook() {
        Instant deadline = Instant.now().plus(TICK_BUDGET);
        int pages = 0;
        while (Instant.now().isBefore(deadline)) {
            List<WebhookOutbox> outboxes = webhookService.getOutboxes();
            if (outboxes.isEmpty()) {
                return;
            }
            pages++;
            deliverConcurrently(outboxes);
        }
        log.info("Webhook drain hit the time budget. pages={}", pages);
    }

    private void deliverConcurrently(List<WebhookOutbox> outboxes) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            outboxes.forEach(outbox -> executor.execute(() -> dispatcher.deliver(outbox)));
        }
        // executor.close() 호출 시 모든 virtual thread 완료까지 대기
    }
}
