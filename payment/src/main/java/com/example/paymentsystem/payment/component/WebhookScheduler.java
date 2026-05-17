package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.WebhookOutbox;
import com.example.paymentsystem.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookScheduler {

    private final WebhookService webhookService;

    @Qualifier("merchantWebhookRestClient")
    private final RestClient restClient;

    @Scheduled(fixedDelay = 10_000)
    public void webhook() {
        List<WebhookOutbox> outboxes = webhookService.getOutboxes();

        for (WebhookOutbox outbox : outboxes) {
            try {
                String request = outbox.getPayload();

                restClient.post()
                        .uri("/webhooks/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()

                        .toBodilessEntity();

                webhookService.completeWebhook(outbox.getId());

            } catch (Exception e) {
                webhookService.retryWebhook(outbox.getId(), e.getMessage());
            }
        }
    }
}
