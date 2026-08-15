package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.component.InquiryQueue;
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
    private final InquiryQueue inquiryQueue;

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
        // 큐가 들고 있는 예약 수. unknownTx 보다 작으면 인메모리 큐가 일부를 잃었다는 뜻이고,
        // 그 차이는 sweeper가 메운다. 판정에는 쓰지 않고 진단용으로만 노출한다.
        int queuedInquiries     = inquiryQueue.trackedCount();

        // pendingWebhook을 판정에 넣는다. 빼면 "UNKNOWN은 다 풀렸는데 그 사실을 가맹점에
        // 알리는 통로가 수천 건 막혀 있는" 상태를 converged=true로 보고하게 된다 —
        // 150 TPS 실측에서 실제로 pendingWebhook=6583인데 converged=true가 나왔다.
        boolean converged = unknownTx == 0 && staleRequested == 0
                && authenticated == 0 && processingIdempotencyKeys == 0
                && pendingWebhook == 0;

        return new ConvergenceStatus(
                unknownTx, staleRequested, authenticated, fdsPassed, pendingWebhook,
                processingIdempotencyKeys, queuedInquiries, converged);
    }

    public record ConvergenceStatus(
            long unknownTx,
            long staleRequested,
            long authenticated,
            long fdsPassed,
            long pendingWebhook,
            long processingIdempotencyKeys,
            int queuedInquiries,
            boolean converged
    ) {}
}
