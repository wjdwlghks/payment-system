package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.WebhookOutboxStatus;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.example.paymentsystem.payment.repository.WebhookOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/convergence")
@RequiredArgsConstructor
public class ConvergenceController {

    private static final Duration STALE_TX_THRESHOLD       = Duration.ofSeconds(60);
    private static final Duration STALE_IDEMPOTENCY_THRESHOLD = Duration.ofSeconds(90);

    private final PaymentTransactionRepository txRepository;
    private final PaymentIntentRepository intentRepository;
    private final WebhookOutboxRepository outboxRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @GetMapping
    public ConvergenceStatus get() {
        long unknownTx          = txRepository.countByStatus(TransactionStatus.UNKNOWN);
        long staleRequested     = txRepository.countByStatusAndUpdatedAtBefore(
                TransactionStatus.REQUESTED, Instant.now().minus(STALE_TX_THRESHOLD));
        // AUTHENTICATED is the transient recovery state (fds-scheduler continues it to FDS_PASSED).
        // FDS_PASSED is a resting state awaiting the merchant approve call — reported but not a blocker.
        long authenticated          = intentRepository.countByStatus(PaymentIntentStatus.AUTHENTICATED);
        long fdsPassed           = intentRepository.countByStatus(PaymentIntentStatus.FDS_PASSED);
        long pendingWebhook     = outboxRepository.countByStatus(WebhookOutboxStatus.PENDING);
        long processingIdempotencyKeys = idempotencyKeyRepository.countByStatus(IdempotentStatus.PROCESSING);

        boolean converged = unknownTx == 0 && staleRequested == 0
                && authenticated == 0 && processingIdempotencyKeys == 0;

        return new ConvergenceStatus(
                unknownTx, staleRequested, authenticated, fdsPassed, pendingWebhook, processingIdempotencyKeys, converged);
    }

    public record ConvergenceStatus(
            long unknownTx,
            long staleRequested,
            long authenticated,
            long fdsPassed,
            long pendingWebhook,
            long processingIdempotencyKeys,
            boolean converged
    ) {}
}
