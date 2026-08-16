package com.example.paymentsystem.merchant.component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 가맹점 관점의 지연 측정.
 *
 * <p><b>사용자 지연 = 결제 요청을 보낸 시각 → APPROVED를 알게 된 시각.</b> 매입은 포함하지 않는다 —
 * 사용자는 승인 시점에 "결제 완료"를 보기 때문이다. APPROVED를 알게 되는 경로는 두 가지이고
 * (동기 approve 응답 / {@code done} 웹훅) 둘 중 <b>먼저 오는 쪽</b>이 시계를 멈춘다.
 * 그래서 {@link #completeApproved}는 최초 1회만 true를 돌려준다.
 *
 * <p>UNKNOWN을 거친 결제와 그렇지 않은 결제를 나눠 담는다. 후자가 대조군이고, 두 분포의 차이가
 * 곧 "UNKNOWN이 얹은 순수 추가 지연"이다.
 *
 * <p>경과 시간은 {@link System#nanoTime()}으로 잰다 — 벽시계는 NTP 보정에 흔들린다.
 */
@Component
public class LatencyRecorder {

    private final Map<String, Flow> flows = new ConcurrentHashMap<>();
    private final Queue<Sample> userLatency = new ConcurrentLinkedQueue<>();
    private final Queue<Sample> captureLatency = new ConcurrentLinkedQueue<>();

    private final AtomicLong started = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong orphanWebhooks = new AtomicLong();

    /**
     * 결제 요청 등록. {@code startNanos}는 <b>요청을 보내기 직전</b>에 찍은 값이어야 한다 —
     * 응답을 받은 뒤에 찍으면 인증+FDS 왕복이 통째로 측정에서 빠진다.
     *
     * <p>키가 paymentKey가 아니라 orderId인 이유: paymentKey는 payment가 만들어 응답에 실어주므로
     * 요청을 보내기 전에는 알 수 없다. 그런데 즉시 배달이 도입되면서 웹훅이 HTTP 응답을
     * <b>추월</b>할 수 있게 됐고, 그때 paymentKey로 등록돼 있지 않으면 웹훅이 orphan으로 떨어져
     * 그 결제는 지연도 못 재고 매입도 못 나간다. orderId는 가맹점이 만든 값이라 t0 시점에 이미 안다.
     */
    public void start(String orderId, long startNanos, boolean sawUnknown) {
        if (orderId == null) {
            return;
        }
        Flow flow = new Flow(startNanos);
        if (sawUnknown) {
            flow.sawUnknown = true;
        }
        if (flows.putIfAbsent(orderId, flow) == null) {
            started.incrementAndGet();
        }
    }

    /**
     * merchant가 시작한 흐름인가.
     *
     * <p>웹훅 핸들러가 남의 결제까지 몰고 가지 않게 하는 판정이다. payment를 직접 때리는
     * 부하 스크립트가 돌면 merchant는 자기가 만들지도 않은 결제의 웹훅을 받게 되는데,
     * 그때 승인·매입을 걸면 스크립트의 호출과 겹쳐 진짜 동시 중복 요청이 된다.
     */
    public boolean tracks(String orderId) {
        return orderId != null && flows.containsKey(orderId);
    }

    /** 시작 기록이 없는 orderId로 웹훅이 온 경우. */
    public void recordOrphanWebhook() {
        orphanWebhooks.incrementAndGet();
    }

    /** 승인이 UNKNOWN으로 끝났을 때처럼, 시작 이후에 UNKNOWN을 만난 경우. */
    public void markUnknown(String orderId) {
        Flow flow = flows.get(orderId);
        if (flow != null) {
            flow.sawUnknown = true;
        }
    }

    /**
     * APPROVED 확인. 이 호출이 실제로 시계를 멈췄으면 true.
     * 동기 응답과 {@code done} 웹훅이 모두 도착하므로 두 번 불리는 게 정상이고,
     * 두 번째는 false를 받아 매입을 중복 발사하지 않는다.
     */
    public boolean completeApproved(String orderId) {
        Flow flow = flows.get(orderId);
        if (flow == null) {
            orphanWebhooks.incrementAndGet();
            return false;
        }
        if (!flow.completed.compareAndSet(false, true)) {
            return false;
        }
        long elapsedMs = (System.nanoTime() - flow.startNanos) / 1_000_000L;
        userLatency.add(new Sample(elapsedMs, flow.sawUnknown));
        return true;
    }

    /** 인증/FDS/승인 어느 단계든 확정 실패. 지연 분포에서는 제외하고 건수만 센다. */
    public void fail(String orderId) {
        Flow flow = flows.get(orderId);
        if (flow != null && flow.completed.compareAndSet(false, true)) {
            failed.incrementAndGet();
        }
    }

    public void captureFired(String orderId) {
        Flow flow = flows.get(orderId);
        if (flow != null) {
            flow.captureFiredNanos = System.nanoTime();
        }
    }

    /**
     * 매입 확정. 사용자 지연과는 별개 지표다.
     *
     * <p>동기 매입 응답과 {@code captured} 웹훅이 <b>둘 다</b> 도착하므로 두 번 불린다 —
     * CAS로 최초 1회만 표본에 넣는다.
     */
    public void captureResolved(String orderId) {
        Flow flow = flows.get(orderId);
        if (flow == null || flow.captureFiredNanos == 0L) {
            return;
        }
        if (!flow.captureRecorded.compareAndSet(false, true)) {
            return;
        }
        long elapsedMs = (System.nanoTime() - flow.captureFiredNanos) / 1_000_000L;
        captureLatency.add(new Sample(elapsedMs, flow.sawUnknown));
    }

    public Snapshot snapshot(int workerQueueDepth, int workerActive) {
        List<Long> normal = new ArrayList<>();
        List<Long> unknown = new ArrayList<>();
        for (Sample s : userLatency) {
            (s.sawUnknown() ? unknown : normal).add(s.elapsedMs());
        }
        List<Long> capture = new ArrayList<>();
        for (Sample s : captureLatency) {
            capture.add(s.elapsedMs());
        }

        Map<String, Stats> user = new LinkedHashMap<>();
        user.put("normal", Stats.of(normal));
        user.put("viaUnknown", Stats.of(unknown));

        long completedCount = normal.size() + unknown.size();
        long inFlight = started.get() - completedCount - failed.get();

        return new Snapshot(
                user,
                Stats.of(capture),
                started.get(),
                completedCount,
                failed.get(),
                Math.max(inFlight, 0),
                orphanWebhooks.get(),
                workerQueueDepth,
                workerActive
        );
    }

    public void reset() {
        flows.clear();
        userLatency.clear();
        captureLatency.clear();
        started.set(0);
        failed.set(0);
        orphanWebhooks.set(0);
    }

    /**
     * 한 번 등록한 Flow는 {@link #reset()} 전까지 지우지 않는다.
     *
     * <p>확정 후 지워버리면 뒤늦게 도착하는 웹훅(정상 결제도 동기 응답과 웹훅을 <b>둘 다</b> 받는다)이
     * 엔트리를 못 찾아 orphan으로 잡힌다 — 그러면 orphanWebhooks가 모든 결제마다 올라가서
     * "측정 구멍 신호"라는 의미를 잃는다. 대신 플래그로 중복을 걸러낸다.
     */
    private static final class Flow {
        final long startNanos;
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicBoolean captureRecorded = new AtomicBoolean(false);
        volatile boolean sawUnknown;
        volatile long captureFiredNanos;

        Flow(long startNanos) {
            this.startNanos = startNanos;
        }
    }

    private record Sample(long elapsedMs, boolean sawUnknown) {}

    public record Stats(long count, long p50Ms, long p90Ms, long p95Ms, long p99Ms, long maxMs) {

        static Stats of(List<Long> values) {
            if (values.isEmpty()) {
                return new Stats(0, 0, 0, 0, 0, 0);
            }
            Collections.sort(values);
            return new Stats(
                    values.size(),
                    percentile(values, 0.50),
                    percentile(values, 0.90),
                    percentile(values, 0.95),
                    percentile(values, 0.99),
                    values.get(values.size() - 1)
            );
        }

        private static long percentile(List<Long> sorted, double q) {
            int index = (int) Math.ceil(q * sorted.size()) - 1;
            return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
        }
    }

    /**
     * @param inFlight       시작했지만 아직 APPROVED에도 실패에도 도달 못 한 건수.
     *                       0으로 안 떨어지면 분포에서 <b>가장 느린 건들이 통째로 빠진</b> 것이므로
     *                       그 런의 지연값은 낙관 편향이다.
     * @param orphanWebhooks 시작 기록이 없는 orderId로 온 웹훅. reset 이후 잔여분이 아니라면
     *                       측정 로직에 구멍이 있다는 신호.
     * @param workerQueueDepth 워커 대기 큐 깊이. 계속 쌓였다면 merchant가 병목이었다는 뜻이고,
     *                       그 런의 지연값에는 merchant 자체 대기가 섞여 있어 버려야 한다.
     */
    public record Snapshot(
            Map<String, Stats> userLatency,
            Stats captureLatency,
            long started,
            long completed,
            long failed,
            long inFlight,
            long orphanWebhooks,
            int workerQueueDepth,
            int workerActive
    ) {}
}
