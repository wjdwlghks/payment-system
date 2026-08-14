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
 *
 * <p><b>모든 상태는 orderId로 키잉한다.</b> payment가 커밋 직후 즉시 웹훅을 배달하게 되면서
 * 웹훅이 HTTP 응답을 추월할 수 있게 됐고, paymentKey는 그 응답에서만 알 수 있기 때문이다.
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

    /**
     * 요청을 <b>보내기 전에</b> 시계를 켠다. 반환된 orderId를 응답 처리에 그대로 넘긴다.
     *
     * <p>등록이 발신보다 먼저여야 하는 이유는 두 가지다. 하나는 t0에 인증+FDS 왕복이 포함돼야
     * 하기 때문이고, 다른 하나는 payment가 커밋 즉시 웹훅을 쏘기 때문에 <b>응답보다 웹훅이
     * 먼저 도착할 수 있기</b> 때문이다. 응답을 받고 등록하면 그 웹훅이 갈 곳을 잃는다.
     */
    public String begin(String body) {
        JsonNode request = parse(body);
        String key = flowKey(text(request, "merchantId"), text(request, "orderId"));
        if (key == null) {
            log.warn("payment request without merchantId/orderId; latency will not be tracked");
            return null;
        }
        recorder.start(key, System.nanoTime(), false);
        return key;
    }

    /**
     * 흐름 식별자. payment의 {@code PAYMENT_REQUEST} 멱등키와 같은 조합을 쓴다 —
     * orderId는 <b>가맹점 단위로만</b> 유일하므로 그것만으로 키잉하면 서로 다른 가맹점이
     * 같은 주문번호를 쓸 때 두 결제가 한 칸을 공유해 한쪽이 통째로 누락된다.
     */
    private String flowKey(String merchantId, String orderId) {
        if (merchantId == null || orderId == null) {
            return null;
        }
        return merchantId + ":" + orderId;
    }

    public void onPaymentResponse(String orderId, ResponseEntity<String> response) {
        if (orderId == null) {
            return;
        }
        if (response.getStatusCode().value() != 200) {
            recorder.fail(orderId);
            return;
        }
        JsonNode body = parse(response.getBody());
        String paymentKey = text(body, "paymentKey");
        String status = text(body, "status");
        if (status == null) {
            return;
        }

        if (status.startsWith("UNKNOWN")) {
            recorder.markUnknown(orderId);
            return;                                  // ready 웹훅이 이어받는다
        }
        switch (status) {
            case "FDS_PASSED" -> approve(orderId, paymentKey);
            case "AUTH_FAILED", "FDS_FAILED" -> recorder.fail(orderId);
            default -> { }
        }
    }

    // ── 웹훅 경로 ────────────────────────────────────────────────────────────

    /**
     * 웹훅 접수. <b>여기서 실제 작업을 하면 안 된다.</b>
     *
     * <p>payment의 배달 클라이언트는 응답을 기다리고, 배달은 커밋한 스레드(가맹점 API의 Tomcat
     * 스레드이거나 UNKNOWN을 복구 중인 inquiry 스케줄러 스레드)가 넘긴 작업이다. merchant가
     * 여기서 승인 호출(수백 ms~수 초)을 하면 그 대기가 payment의 배달 워커를 잡고,
     * 스케줄러가 안전망으로 도는 경로에서는 복구 루프까지 함께 늘어진다.
     *
     * <p>중복 제거가 필요한 이유: 배달은 at-least-once다. {@code deliver()}의 try 블록 안에
     * {@code completeWebhook}이 들어 있어 배달 성공 후 완료 마킹이 실패하면 재배달되고,
     * 즉시 배달과 스케줄러가 같은 행을 동시에 집을 수도 있다. payment 쪽 정합성은 멱등키가
     * 지켜주지만 <b>지연 통계는 이중 계상으로 오염된다.</b>
     */
    public void enqueueWebhook(PaymentWebhookRequest request) {
        if (request.eventId() != null && !processedEventIds.add(request.eventId())) {
            return;
        }
        worker.execute(() -> {
            try {
                onWebhook(request);
            } catch (Exception e) {
                log.warn("webhook handling failed. eventType={}, orderId={}",
                        request.eventType(), request.orderId(), e);
            }
        });
    }

    private void onWebhook(PaymentWebhookRequest request) {
        String orderId = flowKey(request.merchantId(), request.orderId());
        String paymentKey = request.paymentKey();
        if (orderId == null) {
            return;
        }
        switch (request.eventType()) {
            case "ready"    -> approve(orderId, paymentKey);
            case "done"     -> onApproved(orderId, paymentKey);
            case "captured" -> recorder.captureResolved(orderId);
            case "failed"   -> {
                if ("CAPTURE".equals(request.failedStage())) {
                    recorder.captureResolved(orderId);
                } else {
                    recorder.fail(orderId);
                }
            }
            default -> log.debug("unhandled webhook eventType={}", request.eventType());
        }
    }

    // ── 단계 ────────────────────────────────────────────────────────────────

    private void approve(String orderId, String paymentKey) {
        if (orderId == null || paymentKey == null || !approveInitiated.add(orderId)) {
            return;
        }
        try {
            ResponseEntity<String> response = paymentClient.approve(paymentKey);
            int code = response.getStatusCode().value();
            if (code == 422) {
                recorder.fail(orderId);
                return;
            }
            if (code != 200) {
                // 409 = 이미 진행 중(UNKNOWN) → done 웹훅이 마무리한다
                recorder.markUnknown(orderId);
                return;
            }

            String status = text(parse(response.getBody()), "status");
            if ("APPROVED".equals(status)) {
                onApproved(orderId, paymentKey);
            } else if (status != null && status.startsWith("UNKNOWN")) {
                recorder.markUnknown(orderId);
            } else if ("APPROVE_FAILED".equals(status)) {
                recorder.fail(orderId);
            }
        } catch (Exception e) {
            // ready 웹훅이 다시 시도할 수 있도록 초기화 표시를 되돌린다
            approveInitiated.remove(orderId);
            log.warn("approve call failed. orderId={}", orderId, e);
        }
    }

    /**
     * 승인 확정 지점. 동기 응답과 {@code done} 웹훅이 모두 도달하므로 두 번 불릴 수 있고,
     * {@code completeApproved}가 최초 1회만 true를 돌려줘 매입 중복 발사를 막는다.
     *
     * <p>지연을 먼저 확정하고 그 다음에 매입을 발사한다 — 순서가 반대면 매입 호출 시간이
     * 사용자 지연에 섞인다.
     */
    private void onApproved(String orderId, String paymentKey) {
        if (orderId == null || paymentKey == null || !recorder.completeApproved(orderId)) {
            return;
        }
        worker.execute(() -> {
            try {
                capture(orderId, paymentKey);
            } catch (Exception e) {
                log.warn("capture call failed. orderId={}", orderId, e);
            }
        });
    }

    private void capture(String orderId, String paymentKey) {
        // 매입 지연은 payment의 처리 시간을 재는 것이므로, merchant 워커 큐에서 대기한 시간이
        // 섞이지 않도록 실제 호출 직전에 시작 시각을 찍는다.
        recorder.captureFired(orderId);

        ResponseEntity<String> response = paymentClient.capture(paymentKey);
        if (response.getStatusCode().value() != 200) {
            return;
        }
        String captureStatus = text(parse(response.getBody()), "captureStatus");
        if ("SUCCEEDED".equals(captureStatus) || "FAIL".equals(captureStatus)) {
            recorder.captureResolved(orderId);
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
            log.warn("unparseable json: {}", body, e);
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
