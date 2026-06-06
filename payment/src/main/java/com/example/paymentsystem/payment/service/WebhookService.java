package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.Refund;
import com.example.paymentsystem.payment.domain.WebhookOutbox;
import com.example.paymentsystem.payment.domain.WebhookOutboxStatus;
import com.example.paymentsystem.payment.dto.PaymentWebhookRequest;
import com.example.paymentsystem.payment.dto.RefundWebhookRequest;
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
    private static final String REFUND_STATUS_CHANGED = "REFUND_STATUS_CHANGED";
    private static final String STAGE_AUTH = "AUTH";
    private static final String STAGE_FDS = "FDS";
    private static final String STAGE_CAPTURE = "CAPTURE";
    private static final String WEBHOOK_READY_FOR_CONFIRM = "ready";
    private static final String WEBHOOK_DONE = "done";
    private static final String WEBHOOK_FAILED = "failed";
    private static final String WEBHOOK_REFUND_SUCCEEDED = "succeeded";
    private static final String WEBHOOK_REFUND_FAILED = "failed";


    private final ObjectMapper objectMapper;
    private final WebhookOutboxRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReadyForConfirm(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_READY_FOR_CONFIRM,null);
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
    public void saveCaptureFailed(PaymentIntent paymentIntent) {
        save(paymentIntent, WEBHOOK_FAILED, STAGE_CAPTURE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveRefundSucceeded(Refund refund) {
        saveRefund(refund, WEBHOOK_REFUND_SUCCEEDED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveRefundFailed(Refund refund) {
        saveRefund(refund, WEBHOOK_REFUND_FAILED);
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

    private void saveRefund(Refund refund, String webhookEventType) {
        String eventId = UUID.randomUUID().toString();
        PaymentIntent paymentIntent = refund.getPaymentIntent();

        RefundWebhookRequest request = new RefundWebhookRequest(
                eventId,
                webhookEventType,
                refund.getRefundKey(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                refund.getAmount(),
                refund.getStatus().name(),
                Instant.now()
        );

        String payload = objectMapper.writeValueAsString(request);

        WebhookOutbox outbox = new WebhookOutbox(
                eventId,
                refund.getRefundKey(),
                REFUND_STATUS_CHANGED,
                payload,
                Instant.now()
        );

        repository.save(outbox);
    }

    @Transactional(readOnly = true)
    public List<WebhookOutbox> getOutboxes() {
        return repository.findTop150ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
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
