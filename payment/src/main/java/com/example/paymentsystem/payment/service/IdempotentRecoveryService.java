package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.Refund;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.RefundRepository;
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
    private static final String REFUND_KEY_SEPARATOR = ":refund:";

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
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
            case PAYMENT_CONFIRM -> recoverPaymentConfirm(key.getIdempotentKey());
            case PAYMENT_REFUND -> recoverPaymentRefund(key.getIdempotentKey());
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
            case AUTH_REQUESTED, UNKNOWN_AUTH, AUTH_READY,
                 FDS_REQUESTED, UNKNOWN_FDS -> Optional.empty();
            case AUTH_FAILED, FDS_FAILED -> Optional.of(intentOutcome(pi, 422));
            default -> Optional.of(intentOutcome(pi, 200));
        };
    }

    private Optional<RecoveryOutcome> recoverPaymentConfirm(String idempotentKey) {
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
        // confirm phase now performs CAPTURE only; FDS_READY means capture not yet started
        return switch (status) {
            case AUTH_REQUESTED, UNKNOWN_AUTH, AUTH_READY,
                 FDS_REQUESTED, UNKNOWN_FDS, FDS_READY,
                 CAPTURE_REQUESTED, UNKNOWN_CAPTURE -> Optional.empty();
            case AUTH_FAILED, FDS_FAILED, CAPTURE_FAILED -> Optional.of(intentOutcome(pi, 422));
            default -> Optional.of(intentOutcome(pi, 200));
        };
    }

    private Optional<RecoveryOutcome> recoverPaymentRefund(String idempotentKey) {
        int idx = idempotentKey.indexOf(REFUND_KEY_SEPARATOR);
        if (idx < 0) {
            return Optional.empty();
        }
        String refundKey = idempotentKey.substring(idx + REFUND_KEY_SEPARATOR.length());
        Optional<Refund> refund = refundRepository.findByRefundKey(refundKey);
        if (refund.isEmpty()) {
            return Optional.empty();
        }
        Refund r = refund.get();
        return switch (r.getStatus()) {
            case REQUESTED, UNKNOWN -> Optional.empty();
            case FAIL -> Optional.of(refundOutcome(r, 422));
            case SUCCEEDED -> Optional.of(refundOutcome(r, 200));
        };
    }

    private RecoveryOutcome intentOutcome(PaymentIntent intent, int code) {
        String body = String.format(
                "{\"recovered\":true,\"paymentKey\":\"%s\",\"status\":\"%s\"}",
                intent.getPaymentKey(),
                intent.getStatus().name()
        );
        return new RecoveryOutcome(code, body);
    }

    private RecoveryOutcome refundOutcome(Refund refund, int code) {
        String body = String.format(
                "{\"recovered\":true,\"refundKey\":\"%s\",\"status\":\"%s\"}",
                refund.getRefundKey(),
                refund.getStatus().name()
        );
        return new RecoveryOutcome(code, body);
    }

    private record RecoveryOutcome(int code, String body) {
    }
}
