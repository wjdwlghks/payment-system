package com.example.paymentsystem.payment.component;


import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.service.IdempotentRecoveryService;
import com.example.paymentsystem.payment.service.InquiryService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InquiryScheduler {

    /**
     * 한 번의 SELECT/UPDATE로 다룰 행 수. <b>처리율 상한이 아니다</b> — drain 루프가 due가
     * 빌 때까지 반복해서 가져가므로, 처리율은 예산 × 병렬도가 정한다. 부하가 늘어도
     * 이 값을 올릴 이유가 없다(메모리와 예산 오버슈트 폭만 정한다).
     */
    private static final int PAGE_SIZE = 300;

    /**
     * 한 틱이 스레드를 쥘 수 있는 벽시계 상한. drain 루프의 정상 종료 조건은 "due 없음"인데,
     * 부하 중에는 처리하는 속도만큼 새 UNKNOWN이 생겨 루프가 안 끝날 수 있다.
     *
     * <p>페이지 <b>사이</b>에서만 검사하므로 실제 최대는 {@code 예산 + 페이지 1개 최악 소요시간}이다.
     * 카드사가 멈춰 건당 3초 타임아웃이면 300건/20병렬 = 45초까지 오버슈트한다.
     * 초과해도 손실은 없다 — 아직 선점하지 않은 행은 여전히 due 상태로 다음 틱이 이어받는다.
     */
    private static final Duration TICK_BUDGET = Duration.ofSeconds(5);

    /**
     * 대상(카드사 / FDS)별 동시 조회 수. 클라이언트 커넥션 풀의 {@code maxConnPerRoute}(20)에 맞춘다.
     *
     * <p>풀보다 크게 잡으면 초과분이 {@code ConnectionRequestTimeout}으로 실패하는데,
     * 그건 "카드사가 응답을 안 했다"가 아니라 "우리 쪽 커넥션이 없었다"이다. 그런데도 백오프
     * 사다리는 한 칸 올라가므로, 카드사에 질문조차 못 해본 행이 순전히 로컬 자원 부족 때문에
     * 캡까지 유배된다. 백오프 신호를 오염시키지 않으려면 풀 안에서 놀아야 한다.
     *
     * <p>적응형 리미터({@code CardConcurrencyLimiterRegistry})를 재사용하지 않는 이유는 방향이
     * 반대이기 때문이다. 그쪽은 카드사가 느려지면 한도를 <b>줄이는데</b>, 느려지는 그 시점이
     * 바로 UNKNOWN이 가장 많이 쌓여 복구가 가장 필요한 때다.
     */
    private static final int PER_TARGET_CONCURRENCY = 20;

    private static final String FDS_TARGET = "FDS";

    private final InquiryService inquiryService;
    private final IdempotentRecoveryService idempotentRecoveryService;

    private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

    /**
     * 주기가 곧 UNKNOWN 복구 지연의 하한이다 — 150 TPS 실측에서 viaUnknown p50 9.2초가
     * 나왔고 그 대부분이 이 틱을 기다린 시간이었다. 백오프가 들어오기 전에는 주기를 줄이면
     * 죽은 카드사를 그만큼 더 때렸지만, 이제 해소 불가능한 건은 스스로 물러나므로
     * 주기를 줄여도 살아 있는 건들만 더 자주 확인하게 된다.
     */
    @Scheduled(fixedDelayString = "${payment.inquiry.interval-ms:5000}")
    public void inquiryUnknownPayment() {
        drain(inquiryService::claimDueUnknowns, "unknown");
    }

    @Scheduled(fixedDelay = 30_000)
    public void inquiryStaleRequested() {
        drain(inquiryService::claimDueStaleRequested, "stale-requested");
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

    /**
     * due가 빌 때까지 페이지 단위로 소진한다.
     *
     * <p>루프가 반드시 끝나는 이유는 선점(claim)이 가져간 행을 전부 미래로 밀기 때문이다 —
     * 다음 {@code claim}은 반드시 서로소인 집합을 돌려주므로 단조 전진한다.
     * 선점이 없으면 같은 300건을 예산이 탈 때까지 반복 조회하며 진행이 0이 된다.
     */
    private void drain(IntFunction<List<PaymentTransaction>> claimer, String label) {
        Instant deadline = Instant.now().plus(TICK_BUDGET);
        int pages = 0;
        while (Instant.now().isBefore(deadline)) {
            List<PaymentTransaction> page;
            try {
                page = claimer.apply(PAGE_SIZE);
            } catch (Exception e) {
                log.error("Failed to claim inquiry page. kind={}", label, e);
                return;
            }
            if (page.isEmpty()) {
                return;
            }
            pages++;
            inquireConcurrently(page);
        }
        log.info("Inquiry drain hit the time budget. kind={} pages={}", label, pages);
    }

    /**
     * 대상별로 동시 진행 수를 제한해 함께 발사한다.
     *
     * <p>순차 루프였을 때는 멈춘 카드사 하나가 배치 전체를 붙잡았다 —
     * 300건 × 3초 타임아웃이면 한 틱이 15분이고, 그 뒤에 섞인 건강한 카드사의 결제도 같이 밀렸다.
     * 대상별 세마포어로 나누면 느린 쪽은 자기 20칸 안에서만 밀리고 나머지는 즉시 끝난다.
     */
    private void inquireConcurrently(List<PaymentTransaction> page) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PaymentTransaction transaction : page) {
                executor.execute(() -> inquireWithSlot(transaction));
            }
        }
        // executor.close() — 페이지의 모든 조회가 끝날 때까지 대기
    }

    private void inquireWithSlot(PaymentTransaction transaction) {
        Semaphore slot = slots.computeIfAbsent(
                targetOf(transaction), k -> new Semaphore(PER_TARGET_CONCURRENCY));
        slot.acquireUninterruptibly();
        try {
            inquire(transaction);
        } finally {
            slot.release();
        }
    }

    /** FDS는 카드사와 별개 서비스라 커넥션 풀도 따로다 — 한도도 따로 센다. */
    private String targetOf(PaymentTransaction transaction) {
        if (transaction.getType() == TransactionType.FDS) {
            return FDS_TARGET;
        }
        return transaction.getPaymentIntent().getCardCompany().name();
    }

    private void inquire(PaymentTransaction transaction) {
        try {
            switch (transaction.getType()) {
                case AUTH -> inquiryService.inquiryAuth(transaction);
                case FDS -> inquiryService.inquiryFds(transaction);
                case APPROVE -> inquiryService.inquiryApprove(transaction);
                case CAPTURE -> inquiryService.inquiryCapture(transaction);
            }
        } catch (Exception e) {
            log.error("Failed to inquire stuck transaction. transactionId={} status={}",
                    transaction.getId(), transaction.getStatus(), e);
        }
    }
}
