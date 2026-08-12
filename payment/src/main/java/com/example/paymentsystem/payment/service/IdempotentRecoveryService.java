package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotentRecoveryService {

    private static final Duration STALE_PROCESSING_THRESHOLD = Duration.ofSeconds(90);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final IdempotentService idempotentService;

    @Transactional(readOnly = true)
    public List<IdempotencyKey> getStaleProcessing() {
        Instant threshold = Instant.now().minus(STALE_PROCESSING_THRESHOLD);
        return idempotencyKeyRepository
                .findTop300ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        IdempotentStatus.PROCESSING,
                        threshold
                );
    }

    public void recover(IdempotencyKey key) {
        Optional<RecoveryOutcome> outcome = switch (key.getOperation()) {
            case PAYMENT_REQUEST -> recoverPaymentRequest(key.getIdempotentKey());
            case PAYMENT_APPROVE -> recoverPaymentApprove(key.getIdempotentKey());
            case PAYMENT_CAPTURE -> recoverPaymentCapture(key.getIdempotentKey());
        };
        if (outcome.isEmpty()) {
            return;
        }
        RecoveryOutcome o = outcome.get();
        idempotentService.complete(
                key.getIdempotentKey(),
                key.getOperation(),
                o.code(),
                o.body()
        );
    }

    private Optional<RecoveryOutcome> recoverPaymentRequest(String idempotentKey) {
        String[] parts = idempotentKey.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        Optional<PaymentIntent> intent = paymentIntentRepository
                .findByMerchantIdAndOrderId(parts[0], parts[1]);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        PaymentIntent pi = intent.get();
        // request phase now spans AUTH + FDS
        return switch (pi.getStatus()) {
            case AUTH_REQUESTED, UNKNOWN_AUTH, AUTHENTICATED,
                 FDS_REQUESTED, UNKNOWN_FDS -> Optional.empty();
            case AUTH_FAILED, FDS_FAILED -> Optional.of(intentOutcome(pi, 422));
            default -> Optional.of(intentOutcome(pi, 200));
        };
    }

    private Optional<RecoveryOutcome> recoverPaymentApprove(String idempotentKey) {
        String[] parts = idempotentKey.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        String paymentKey = parts[1];
        Optional<PaymentIntent> intent = paymentIntentRepository.findByPaymentKey(paymentKey);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        PaymentIntent pi = intent.get();
        PaymentIntentStatus status = pi.getStatus();
        // 승인 단계는 APPROVE만 수행한다; FDS_PASSED는 승인이 아직 시작되지 않았다는 뜻
        return switch (status) {
            case AUTH_REQUESTED, UNKNOWN_AUTH, AUTHENTICATED,
                 FDS_REQUESTED, UNKNOWN_FDS, FDS_PASSED,
                 APPROVE_REQUESTED, UNKNOWN_APPROVE -> Optional.empty();
            case AUTH_FAILED, FDS_FAILED, APPROVE_FAILED -> Optional.of(intentOutcome(pi, 422));
            default -> Optional.of(intentOutcome(pi, 200));
        };
    }

    // 매입은 PaymentIntent 상태를 바꾸지 않는다(승인 후에도 APPROVED) — CAPTURE tx 상태로 판정한다.
    private Optional<RecoveryOutcome> recoverPaymentCapture(String idempotentKey) {
        String[] parts = idempotentKey.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        Optional<PaymentIntent> intent = paymentIntentRepository.findByPaymentKey(parts[1]);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        PaymentIntent pi = intent.get();
        Optional<PaymentTransaction> succeeded = paymentTransactionRepository
                .findByPaymentIntentAndTypeAndStatus(pi, TransactionType.CAPTURE, TransactionStatus.SUCCEEDED);
        if (succeeded.isPresent()) {
            return Optional.of(intentOutcome(pi, 200));
        }
        Optional<PaymentTransaction> failed = paymentTransactionRepository
                .findByPaymentIntentAndTypeAndStatus(pi, TransactionType.CAPTURE, TransactionStatus.FAIL);
        // 아직 REQUESTED/UNKNOWN이면 확정 전이다 — InquiryScheduler가 먼저 결론을 내야 한다.
        return failed.map(tx -> intentOutcome(pi, 200));
    }

    private RecoveryOutcome intentOutcome(PaymentIntent intent, int code) {
        String body = String.format(
                "{\"recovered\":true,\"paymentKey\":\"%s\",\"status\":\"%s\"}",
                intent.getPaymentKey(),
                intent.getStatus().name()
        );
        return new RecoveryOutcome(code, body);
    }

    private record RecoveryOutcome(int code, String body) {
    }
}
