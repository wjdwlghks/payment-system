package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.WebhookOutbox;
import com.example.paymentsystem.payment.service.WebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 웹훅 한 건을 실제로 배달하고 결과를 기록한다.
 *
 * <p>두 경로가 공유한다 — 커밋 직후 즉시 배달({@link WebhookDispatchListener})과
 * 아웃박스를 훑는 안전망({@link WebhookScheduler}). 배달 성공/실패 판정과 백오프가
 * 한 곳에만 있어야 두 경로가 어긋나지 않는다.
 */
@Slf4j
@Component
public class WebhookDispatcher {

    private final WebhookService webhookService;
    private final RestClient restClient;

    public WebhookDispatcher(
            WebhookService webhookService,
            @Qualifier("merchantWebhookRestClient") RestClient restClient
    ) {
        this.webhookService = webhookService;
        this.restClient = restClient;
    }

    public void deliver(WebhookOutbox outbox) {
        try {
            restClient.post()
                    .uri("/webhooks/payment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outbox.getPayload())
                    .retrieve()
                    .toBodilessEntity();

            webhookService.completeWebhook(outbox.getId());
        } catch (Exception e) {
            webhookService.retryWebhook(outbox.getId(), e.getMessage());
        }
    }

    /**
     * id로 다시 읽어 아직 PENDING일 때만 배달한다.
     * 즉시 배달과 스케줄러가 같은 행을 동시에 집는 경우를 좁혀준다 —
     * 완전히 막지는 못하지만(둘 다 PENDING을 읽을 수 있다) 가맹점이 eventId로 중복을 걸러낸다.
     */
    public void deliverIfPending(Long outboxId) {
        webhookService.findPending(outboxId).ifPresent(this::deliver);
    }
}
