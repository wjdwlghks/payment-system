package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.WebhookOutbox;
import com.example.paymentsystem.payment.domain.WebhookOutboxStatus;
import com.example.paymentsystem.payment.dto.PaymentWebhookRequest;
import com.example.paymentsystem.payment.repository.WebhookOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";
    private static final String STAGE_AUTH = "AUTH";
    private static final String STAGE_FDS = "FDS";
    private static final String STAGE_APPROVE = "APPROVE";
    private static final String STAGE_CAPTURE = "CAPTURE";
    private static final String WEBHOOK_READY_FOR_APPROVE = "ready";
    private static final String WEBHOOK_DONE = "done";
    private static final String WEBHOOK_CAPTURED = "captured";
    private static final String WEBHOOK_FAILED = "failed";


    private final ObjectMapper objectMapper;
    private final WebhookOutboxRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReadyForApprove(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_READY_FOR_APPROVE,null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePaymentComplete(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_DONE,null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveAuthFailed(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_FAILED, STAGE_AUTH);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveFdsFailed(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_FAILED, STAGE_FDS);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveApproveFailed(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_FAILED, STAGE_APPROVE);
    }

    // 매입은 PaymentIntent 상태를 바꾸지 않으므로 payload의 status는 APPROVED로 남는다 —
    // 가맹점은 status가 아니라 eventType으로 매입 결과를 판정해야 한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveCaptureCompleted(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_CAPTURED, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveCaptureFailed(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_FAILED, STAGE_CAPTURE);
    }


    private void save(PaymentIntent paymentIntent, String webhookEventType, String failStage) {
        String eventId = UUID.randomUUID().toString();

        PaymentWebhookRequest request = new PaymentWebhookRequest(
                eventId,
                webhookEventType,
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                paymentIntent.getStatus().name(),
                failStage,
                Instant.now()
        );

        String payload = objectMapper.writeValueAsString(request);

        WebhookOutbox outbox = new WebhookOutbox(
                eventId,
                paymentIntent.getPaymentKey(),
                PAYMENT_STATUS_CHANGED,
                payload,
                Instant.now()
        );

        repository.save(outbox);
    }

    @Transactional(readOnly = true)
    public List<WebhookOutbox> getOutboxes() {
        return repository.findTop300ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                WebhookOutboxStatus.PENDING,
                Instant.now()
        );
    }

    @Transactional
    public void completeWebhook(Long outboxId) {
        WebhookOutbox outbox = getOutboxForUpdate(outboxId);
        outbox.markSent();
    }

    @Transactional
    public void retryWebhook(Long outboxId, String errorMessage) {
        WebhookOutbox outbox = getOutboxForUpdate(outboxId);
        int nextAttempts = outbox.getAttempts() + 1;

        if (nextAttempts > 7) {
            outbox.markDead(errorMessage);
            return;
        }

        Duration backoff = backoff(nextAttempts);
        outbox.markRetry(Instant.now().plus(backoff), errorMessage);
    }


    private Duration backoff(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(30);
            case 4 -> Duration.ofHours(2);
            case 5 -> Duration.ofHours(6);
            case 6 -> Duration.ofHours(12);
            case 7 -> Duration.ofHours(24);
            default -> null;
        };
    }

    private WebhookOutbox getOutboxForUpdate(Long outboxId) {
        WebhookOutbox outbox = repository.findByIdForUpdate(outboxId);
        if (outbox == null) {
            throw new IllegalArgumentException("Webhook outbox not found: " + outboxId);
        }
        return outbox;
    }

}
