package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.CaptureResponse;
import com.example.paymentsystem.payment.dto.PaymentApiResult;
import com.example.paymentsystem.payment.exception.PaymentValidationException;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 매입(CAPTURE) 상태 전이와 원장 기표.
 * 가맹점이 결제 건별로 요청하는 API라 승인과 같은 멱등키 체계를 쓴다 —
 * 중복 매입은 멱등키가 앞단에서 걸러내고, 최후 방어선은 CAPTURE tx의 payment_intent_id UNIQUE다.
 */
@Service
@RequiredArgsConstructor
public class CaptureCommandService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IdempotentService idempotentService;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    // 병합 트랜잭션: 멱등키 PROCESSING insert + CAPTURE TX insert.
    // 멱등키 삽입이 상태 가드보다 먼저다 — 반대로 두면 이미 매입된 결제의 재요청이 422에 걸려
    // 저장해둔 응답을 replay할 기회가 사라진다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public CaptureRequestContext createCaptureRequestWithIdempotency(String paymentKey) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));

        IdempotencyKey key = IdempotencyKey.builder()
                .idempotentKey(IdempotentKeys.paymentCapture(paymentIntent.getMerchantId(), paymentKey))
                .operation(IdempotencyOperation.PAYMENT_CAPTURE)
                .status(IdempotentStatus.PROCESSING)
                .build();
        idempotencyKeyRepository.saveAndFlush(key);

        // 여기서 던지면 트랜잭션 전체가 롤백되므로 방금 넣은 키도 함께 사라진다.
        if (!paymentIntent.isCapturable()) {
            throw new PaymentValidationException(422, "Payment not capturable: status is " + paymentIntent.getStatus());
        }

        PaymentTransaction captureTransaction = paymentTransactionRepository.save(
                new PaymentTransaction(paymentIntent, TransactionType.CAPTURE, paymentIntent.getAmount())
        );

        return new CaptureRequestContext(
                captureTransaction.getId(),
                paymentIntent.getApprovalId(),
                paymentIntent.getAmount(),
                captureTransaction.getCardRequestRef(),
                paymentIntent.getCardCompany()
        );
    }

    // 매입 확정 시점에 원장을 기표한다 (승인 시점이 아니라).
    // 동기 흐름 · inquiry 복구 · 대사 해소가 모두 이 메서드를 공유한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentApiResult completeCapture(Long captureTransactionId, String externalId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        if (transaction.getStatus() == TransactionStatus.REQUESTED
                || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            transaction.markSucceeded(externalId);
            ledgerService.postCapture(captureTransactionId, sourceType);
        }
        return completeIdempotent(transaction);
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentApiResult failCapture(Long captureTransactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        if (transaction.getStatus() == TransactionStatus.REQUESTED
                || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            if (externalId == null) {
                transaction.markFailWithoutResponse();
            } else {
                transaction.markFail(externalId);
            }
        }
        return completeIdempotent(transaction);
    }

    // UNKNOWN은 확정된 결과가 아니므로 멱등키를 완결하지 않는다 — PROCESSING을 유지한 채
    // InquiryScheduler가 실제 상태를 확정하는 시점에 그 트랜잭션에서 완결된다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentApiResult unknownCapture(Long captureTransactionId) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        if (transaction.getStatus() == TransactionStatus.REQUESTED) {
            transaction.markUnknown();
        }
        return toApiResult(transaction);
    }

    // 멱등키는 tx가 속한 PaymentIntent에서 재구성한다 — 복구 경로가 키를 들고 다닐 필요가 없다.
    private PaymentApiResult completeIdempotent(PaymentTransaction transaction) {
        PaymentIntent intent = transaction.getPaymentIntent();
        String responseBody = objectMapper.writeValueAsString(toResponse(transaction));
        idempotentService.complete(
                IdempotentKeys.paymentCapture(intent.getMerchantId(), intent.getPaymentKey()),
                IdempotencyOperation.PAYMENT_CAPTURE,
                200,
                responseBody
        );
        return new PaymentApiResult(200, responseBody);
    }

    private PaymentApiResult toApiResult(PaymentTransaction transaction) {
        return new PaymentApiResult(200, objectMapper.writeValueAsString(toResponse(transaction)));
    }

    private CaptureResponse toResponse(PaymentTransaction transaction) {
        PaymentIntent intent = transaction.getPaymentIntent();
        return new CaptureResponse(
                intent.getPaymentKey(),
                intent.getOrderId(),
                transaction.getAmount(),
                transaction.getStatus()
        );
    }

    private PaymentTransaction getTransaction(Long transactionId) {
        return paymentTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
    }
}
