package com.example.paymentsystem.merchant.component;

import com.example.paymentsystem.merchant.client.PaymentClient;
import com.example.paymentsystem.merchant.dto.PaymentWebhookRequest;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가맹점 측 결제 흐름 오케스트레이션.
 *
 * <p>이 클래스가 있는 이유는 <b>UNKNOWN이 난 결제도 끝까지 진행시키기 위해서</b>다. 기존 부하
 * 스크립트는 UNKNOWN 응답을 받으면 그 결제를 버렸고, 그래서 inquiry가 복구해놓은 결제에 아무도
 * 승인을 걸지 않아 "결제 완료"에 도달한 적이 없었다 — 잴 지연 자체가 존재하지 않았다.
 *
 * <p>흐름:
 * <pre>
 *   결제 요청 응답
 *     ├ FDS_PASSED   → 그 자리에서 승인 (요청 스레드)
 *     ├ UNKNOWN_*    → ready 웹훅을 기다렸다가 승인
 *     └ *_FAILED     → 실패 종료
 *   승인 응답
 *     ├ APPROVED         → 지연 확정 + 매입 즉시 발사
 *     ├ UNKNOWN_APPROVE  → done 웹훅을 기다렸다가 지연 확정 + 매입
 *     └ APPROVE_FAILED   → 실패 종료
 * </pre>
 *
 * <p>정상 건은 동기 응답만으로 끝나고 UNKNOWN 건만 웹훅 콜백을 탄다. 그래서 정상 건의 지연이
 * 그대로 대조군이 된다.
 */
@Slf4j
@Component
public class PaymentFlow {

    /**
     * 워커 동시성. 가상 스레드 대신 고정 플랫폼 풀을 쓰는 이유는 관측 때문이다 —
     * {@code getQueue().size()}로 대기 깊이가 그냥 나온다. merchant가 병목이면 그 대기가
     * 지연 측정값에 섞이므로, 사후에 그 런을 버릴지 판단하려면 이 숫자가 반드시 필요하다.
     *
     * <p>큐는 무제한이다. 웹훅은 이미 204로 ACK돼서 재시도해줄 주체가 없으므로,
     * 포화 시 거절(RejectedExecutionException)하면 그 결제가 영구 미완결로 남는다.
     */
    private static final int WORKER_CONCURRENCY = 64;

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            WORKER_CONCURRENCY, WORKER_CONCURRENCY,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()
    );

    /** 웹훅 at-least-once 중복 제거. 상세는 {@link #enqueueWebhook} 참고. */
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    /** 동기 경로와 ready 웹훅이 같은 결제에 승인을 두 번 걸지 않도록. */
    private final Set<String> approveInitiated = ConcurrentHashMap.newKeySet();

    private final PaymentClient paymentClient;
    private final LatencyRecorder recorder;
    private final ObjectMapper objectMapper;

    public PaymentFlow(PaymentClient paymentClient, LatencyRecorder recorder, ObjectMapper objectMapper) {
        this.paymentClient = paymentClient;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
    }

    public int queueDepth() {
        return worker.getQueue().size();
    }

    public int activeWorkers() {
        return worker.getActiveCount();
    }

    // ── 동기 경로 ────────────────────────────────────────────────────────────

    /** @param startNanos 요청을 <b>보내기 직전</b>에 찍은 시각. */
    public void onPaymentResponse(long startNanos, ResponseEntity<String> response) {
        if (response.getStatusCode().value() != 200) {
            return;
        }
        JsonNode body = parse(response.getBody());
        if (body == null) {
            return;
        }
        String paymentKey = text(body, "paymentKey");
        String status = text(body, "status");
        if (paymentKey == null || status == null) {
            return;
        }

        recorder.start(paymentKey, startNanos, status.startsWith("UNKNOWN"));

        switch (status) {
            case "FDS_PASSED" -> approve(paymentKey);
            case "AUTH_FAILED", "FDS_FAILED" -> recorder.fail(paymentKey);
            default -> { /* UNKNOWN_AUTH / UNKNOWN_FDS → ready 웹훅 대기 */ }
        }
    }

    // ── 웹훅 경로 ────────────────────────────────────────────────────────────

    /**
     * 웹훅 접수. <b>여기서 실제 작업을 하면 안 된다.</b>
     *
     * <p>payment의 배달 클라이언트는 응답을 기다리고, {@code WebhookScheduler}는
     * {@code executor.close()}로 배치 전체의 완료를 기다리며, 스케줄러 스레드는 1개다.
     * merchant가 여기서 승인 호출(수백 ms~수 초)을 하면 그 대기가 payment의 웹훅 틱을 잡고,
     * 같은 스레드를 쓰는 inquiry·FDS·잔액 플러시까지 함께 멈춘다. 최악의 경우 merchant가
     * 기다리는 UNKNOWN을 해소해줄 inquiry가 바로 그 대기 때문에 못 도는 순환 정지가 된다.
     *
     * <p>중복 제거가 필요한 이유: 배달은 at-least-once다. {@code deliver()}의 try 블록 안에
     * {@code completeWebhook}이 들어 있어 배달 성공 후 완료 마킹이 실패하면 재배달되고,
     * {@code /admin/scheduler/run-now}는 스케줄 틱과 동시에 돌 수 있는데 대상 조회에 락이 없다.
     * payment 쪽 정합성은 멱등키가 지켜주지만 <b>지연 통계는 이중 계상으로 오염된다.</b>
     */
    public void enqueueWebhook(PaymentWebhookRequest request) {
        if (request.eventId() != null && !processedEventIds.add(request.eventId())) {
            return;
        }
        worker.execute(() -> {
            try {
                onWebhook(request);
            } catch (Exception e) {
                log.warn("webhook handling failed. eventType={}, paymentKey={}",
                        request.eventType(), request.paymentKey(), e);
            }
        });
    }

    private void onWebhook(PaymentWebhookRequest request) {
        String paymentKey = request.paymentKey();
        switch (request.eventType()) {
            case "ready"    -> approve(paymentKey);
            case "done"     -> onApproved(paymentKey);
            case "captured" -> recorder.captureResolved(paymentKey);
            case "failed"   -> {
                if ("CAPTURE".equals(request.failedStage())) {
                    recorder.captureResolved(paymentKey);
                } else {
                    recorder.fail(paymentKey);
                }
            }
            default -> log.debug("unhandled webhook eventType={}", request.eventType());
        }
    }

    // ── 단계 ────────────────────────────────────────────────────────────────

    private void approve(String paymentKey) {
        if (paymentKey == null || !approveInitiated.add(paymentKey)) {
            return;
        }
        try {
            ResponseEntity<String> response = paymentClient.approve(paymentKey);
            int code = response.getStatusCode().value();
            if (code == 422) {
                recorder.fail(paymentKey);
                return;
            }
            if (code != 200) {
                // 409 = 이미 진행 중(UNKNOWN) → done 웹훅이 마무리한다
                recorder.markUnknown(paymentKey);
                return;
            }

            String status = text(parse(response.getBody()), "status");
            if ("APPROVED".equals(status)) {
                onApproved(paymentKey);
            } else if (status != null && status.startsWith("UNKNOWN")) {
                recorder.markUnknown(paymentKey);
            } else if ("APPROVE_FAILED".equals(status)) {
                recorder.fail(paymentKey);
            }
        } catch (Exception e) {
            // ready 웹훅이 다시 시도할 수 있도록 초기화 표시를 되돌린다
            approveInitiated.remove(paymentKey);
            log.warn("approve call failed. paymentKey={}", paymentKey, e);
        }
    }

    /**
     * 승인 확정 지점. 동기 응답과 {@code done} 웹훅이 모두 도달하므로 두 번 불릴 수 있고,
     * {@code completeApproved}가 최초 1회만 true를 돌려줘 매입 중복 발사를 막는다.
     *
     * <p>지연을 먼저 확정하고 그 다음에 매입을 발사한다 — 순서가 반대면 매입 호출 시간이
     * 사용자 지연에 섞인다.
     */
    private void onApproved(String paymentKey) {
        if (paymentKey == null || !recorder.completeApproved(paymentKey)) {
            return;
        }
        worker.execute(() -> {
            try {
                capture(paymentKey);
            } catch (Exception e) {
                log.warn("capture call failed. paymentKey={}", paymentKey, e);
            }
        });
    }

    private void capture(String paymentKey) {
        // 매입 지연은 payment의 처리 시간을 재는 것이므로, merchant 워커 큐에서 대기한 시간이
        // 섞이지 않도록 실제 호출 직전에 시작 시각을 찍는다.
        recorder.captureFired(paymentKey);

        ResponseEntity<String> response = paymentClient.capture(paymentKey);
        if (response.getStatusCode().value() != 200) {
            return;
        }
        String captureStatus = text(parse(response.getBody()), "captureStatus");
        if ("SUCCEEDED".equals(captureStatus) || "FAIL".equals(captureStatus)) {
            recorder.captureResolved(paymentKey);
        }
        // UNKNOWN → captured / failed(CAPTURE) 웹훅이 확정한다
    }

    public void reset() {
        processedEventIds.clear();
        approveInitiated.clear();
    }

    // ── 파싱 ────────────────────────────────────────────────────────────────

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("unparseable payment response: {}", body, e);
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
