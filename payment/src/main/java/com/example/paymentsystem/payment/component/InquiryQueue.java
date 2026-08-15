package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.InquiryClaim;
import com.example.paymentsystem.payment.service.InquiryService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * UNKNOWN 재조회 예약 큐.
 *
 * <p>이전에는 스케줄러가 주기마다 DB를 훑어 "지금 due인 것"을 가져갔다. 그러면 조회 시각이
 * 틱에 정렬되어 <b>주기의 절반이 지연으로 그냥 붙는다</b> — 5초 주기에서 실측한 UNKNOWN 지연
 * p50 5,673ms 중 약 2,500ms가 이 대기였다. {@link DelayQueue}는 예약한 시각에 정확히 깨어나므로
 * 그 성분이 사라진다.
 *
 * <p>{@code DelayQueue}는 내부가 우선순위 큐라 <b>가장 빨리 만료되는 원소가 먼저</b> 나온다.
 * 지연이 짧은 건이 뒤로 밀리는 일이 없다.
 *
 * <p><b>큐는 힙에 있고 프로세스와 함께 사라진다.</b> 그래서 durable한 예약은 여전히
 * {@code transaction.next_inquiry_at}에 적히고, 두 장치가 그걸 받쳐준다 —
 * 재기동 시 {@link InquiryQueueLoader}가 남은 지연 그대로 복원하고,
 * {@link InquiryScheduler}의 sweeper가 due를 한참 넘긴 건(= 큐가 잃어버린 건)을 다시 넣는다.
 *
 * <p>알려진 한계: 큐 크기에 상한이 없다. 카드사가 장시간 죽으면 태스크가 힙에 계속 쌓인다.
 * 현재 규모에서는 문제되지 않아 미룬 사항이다.
 */
@Slf4j
@Component
public class InquiryQueue {

    /**
     * UNKNOWN 확정 직후 첫 조회까지의 지연. 틱 대기가 사라진 지금 <b>사용자 지연을 지배하는
     * 유일한 튜닝 값</b>이라 설정으로 뺐다.
     *
     * <p>0으로 두지 않은 이유는 방금 응답을 못 준 상대에게 즉시 되묻는 게 값이 낮다고 봤기
     * 때문이다. 다만 실측은 그 가정을 지지하지 않는다 — inquiry 응답의 78%가 {@code not_found}
     * (요청이 카드사에 닿지도 않은 경우)였고 {@code in_progress}는 <b>한 건도 없었다</b>.
     * 즉 이 시스템에서는 즉시 물어도 대부분 그 자리에서 확정된다. 낮출 여지가 크다.
     */
    private final Duration initialDelay;

    public InquiryQueue(
            InquiryService inquiryService,
            @Value("${payment.inquiry.initial-delay-ms:3000}") long initialDelayMs
    ) {
        this.inquiryService = inquiryService;
        this.initialDelay = Duration.ofMillis(initialDelayMs);
    }

    /**
     * 대상(카드사 / FDS)별 동시 조회 수. 클라이언트 커넥션 풀의 {@code maxConnPerRoute}(20)에 맞춘다.
     * 풀보다 크게 잡으면 초과분이 {@code ConnectionRequestTimeout}으로 실패하는데, 그건 카드사가
     * 답을 안 한 게 아니라 우리 커넥션이 없었던 것이다 — 그런데도 백오프는 한 칸 오르므로
     * 질문조차 못 해본 건이 로컬 자원 부족 때문에 캡까지 유배된다.
     */
    private static final int PER_TARGET_CONCURRENCY = 20;

    /** 큐에서 꺼내 넘기기만 하는 스레드. 실제 조회는 아래 workers가 한다. */
    private static final int DISPATCHERS = 2;

    private static final String FDS_TARGET = "FDS";

    private final DelayQueue<InquiryTask> queue = new DelayQueue<>();

    /**
     * 큐에 있거나 처리 중인 id. 이벤트·sweeper·재기동 복원이 같은 건을 중복 적재하는 걸 막는다.
     *
     * <p>꺼낸 뒤에도 <b>처리가 끝날 때까지 남겨둔다</b>. 꺼내자마자 지우면 조회하는 동안
     * sweeper가 같은 건을 다시 넣어 두 번 조회하게 된다.
     */
    private final Set<Long> tracked = ConcurrentHashMap.newKeySet();

    private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

    /**
     * 조회는 전부 가상 스레드에서 한다. 세마포어를 기다리며 블록되는 스레드가 많아지는데,
     * 가상 스레드는 그때 캐리어를 놓아주므로 죽은 카드사 때문에 대기 중인 태스크가
     * 다른 카드사의 조회를 막지 않는다.
     */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService dispatchers;

    private final InquiryService inquiryService;

    public void start() {
        dispatchers = Executors.newFixedThreadPool(DISPATCHERS, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("inquiry-dispatch-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < DISPATCHERS; i++) {
            dispatchers.execute(this::dispatchLoop);
        }
    }

    /** UNKNOWN이 막 확정된 건. 첫 조회는 조금 뒤에 한다. */
    public void scheduleInitial(Long transactionId) {
        schedule(transactionId, initialDelay);
    }

    /** sweeper·재기동 복원이 쓰는 진입점. 이미 큐에 있으면 조용히 무시된다. */
    public void schedule(Long transactionId, Duration delay) {
        if (transactionId == null || !tracked.add(transactionId)) {
            return;
        }
        queue.put(new InquiryTask(transactionId, System.nanoTime() + Math.max(0, delay.toNanos())));
    }

    public int size() {
        return queue.size();
    }

    public int trackedCount() {
        return tracked.size();
    }

    private void dispatchLoop() {
        while (running.get()) {
            InquiryTask task;
            try {
                task = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                continue;
            }
            // 넘기기만 하고 즉시 다음 태스크로. 여기서 조회까지 하면 느린 카드사 한 건이
            // 큐 전체의 소비를 막는다 — 순차 배치 시절의 문제가 그대로 재현된다.
            workers.execute(() -> process(task.transactionId()));
        }
    }

    private void process(Long transactionId) {
        try {
            Optional<InquiryClaim> claimed = inquiryService.claimForAttempt(transactionId);
            if (claimed.isEmpty()) {
                tracked.remove(transactionId);      // 이미 확정된 건
                return;
            }

            PaymentTransaction transaction = claimed.get().transaction();
            Semaphore slot = slots.computeIfAbsent(
                    targetOf(transaction), key -> new Semaphore(PER_TARGET_CONCURRENCY));
            slot.acquireUninterruptibly();
            try {
                inquiryService.inquire(transaction);
            } finally {
                slot.release();
            }

            if (inquiryService.isPendingInquiry(transactionId)) {
                // 아직 미확정 — DB에 적어둔 그 시각으로 다시 예약한다(큐와 DB가 같은 값을 본다).
                queue.put(new InquiryTask(transactionId,
                        System.nanoTime() + claimed.get().nextDelay().toNanos()));
            } else {
                tracked.remove(transactionId);
            }
        } catch (Exception e) {
            // 여기서 tracked를 지우면 sweeper가 due 시각 기준으로 다시 주워간다.
            tracked.remove(transactionId);
            log.error("Inquiry task failed. transactionId={}", transactionId, e);
        }
    }

    /** FDS는 카드사와 별개 서비스라 커넥션 풀도 따로다 — 한도도 따로 센다. */
    private String targetOf(PaymentTransaction transaction) {
        if (transaction.getType() == TransactionType.FDS) {
            return FDS_TARGET;
        }
        return transaction.getPaymentIntent().getCardCompany().name();
    }

    @PreDestroy
    void shutdown() {
        running.set(false);
        if (dispatchers != null) {
            dispatchers.shutdownNow();
        }
        workers.shutdown();
    }

    /** {@code DelayQueue}는 만료 시각 순으로 정렬된다 — 지연이 짧은 것이 먼저 나온다. */
    private record InquiryTask(Long transactionId, long dueAtNanos) implements Delayed {

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(
                    getDelay(TimeUnit.NANOSECONDS),
                    other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
