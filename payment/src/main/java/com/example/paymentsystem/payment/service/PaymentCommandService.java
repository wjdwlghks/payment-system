package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotentKeys;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.*;
import com.example.paymentsystem.payment.exception.PaymentValidationException;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IdempotentService idempotentService;
    private final WebhookService webhookService;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    // 병합 트랜잭션: idempotency PROCESSING insert + PaymentIntent/AUTH TX insert.
    // 유니크 제약 위반 시 DataIntegrityViolationException이 그대로 전파되며 전체가 롤백된다 — 호출부에서 잡아서 기존 키를 재조회한다.
    @Transactional
    public AuthRequestContext createAuthRequestWithIdempotency(PaymentRequest request, String idempotentKey, String requestHash) {
        IdempotencyKey key = IdempotencyKey.builder()
                .idempotentKey(idempotentKey)
                .operation(IdempotencyOperation.PAYMENT_REQUEST)
                .requestHash(requestHash)
                .status(IdempotentStatus.PROCESSING)
                .build();
        idempotencyKeyRepository.saveAndFlush(key);

        PaymentIntent paymentIntent = new PaymentIntent(
                UUID.randomUUID().toString(),
                request.orderId(),
                request.merchantId(),
                request.amount(),
                request.cardCompany()
        );
        paymentIntentRepository.save(paymentIntent);

        PaymentTransaction transaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.AUTH,
                request.amount()
        );
        paymentTransactionRepository.save(transaction);

        return new AuthRequestContext(
                paymentIntent.getId(),
                transaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                transaction.getCardRequestRef()
        );
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse completeAuth(Long transactionId, String externalId, Instant authenticatedAt) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markAuthReady(authenticatedAt);
        transaction.markSucceeded(externalId);
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: completeAuth + createFdsRequest. AUTH 성공 응답을 받은 동기 흐름 전용 —
    // inquiry로 뒤늦게 AUTH가 확정되는 경로는 여전히 completeAuth 단독을 쓰고 FdsScheduler가 뒤이어 FDS를 재개한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public FdsRequestContext completeAuthAndRequestFds(Long transactionId, String externalId, Instant authenticatedAt) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            throw new IllegalStateException(
                    "completeAuthAndRequestFds called on already-terminal transaction: " + transaction.getStatus());
        }
        paymentIntent.markAuthReady(authenticatedAt);
        transaction.markSucceeded(externalId);

        paymentIntent.markFdsRequested();
        PaymentTransaction fdsTransaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.FDS,
                paymentIntent.getAmount()
        );
        paymentTransactionRepository.save(fdsTransaction);

        return new FdsRequestContext(
                paymentIntent.getId(),
                fdsTransaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                fdsTransaction.getCardRequestRef()
        );
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse failAuth(Long transactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markAuthFailed();
        markFail(transaction, externalId);
        webhookService.saveAuthFailed(paymentIntent);
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: failAuth + idempotent complete.
    // AUTH phase가 종료 상태가 되는 지점 — 동기 흐름과 inquiry 복구 경로가 공유한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentApiResult failAuthAndComplete(Long transactionId, String externalId, String idempotentKey) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() == TransactionStatus.REQUESTED || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            paymentIntent.markAuthFailed();
            markFail(transaction, externalId);
            webhookService.saveAuthFailed(paymentIntent);
        }
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, toResponse(paymentIntent));
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse unknownAuth(Long transactionId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markAuthUnknown();
        transaction.markUnknown();
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse unknownFds(Long transactionId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markFdsUnknown();
        transaction.markUnknown();
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public FdsRequestContext createFdsRequest(String paymentKey) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));

        paymentIntent.markFdsRequested();
        PaymentTransaction transaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.FDS,
                paymentIntent.getAmount()
        );

        paymentTransactionRepository.save(transaction);

        return new FdsRequestContext(
                paymentIntent.getId(),
                transaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                transaction.getCardRequestRef()
        );
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse failFds(Long transactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markFdsFailed();
        markFail(transaction, externalId);
        webhookService.saveFdsFailed(paymentIntent);
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: failFds + idempotent complete.
    // request phase가 종료되는 지점 — 동기 흐름 / inquiry / FdsScheduler가 공유한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentApiResult failFdsAndComplete(Long transactionId, String externalId, String idempotentKey) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() == TransactionStatus.REQUESTED || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            paymentIntent.markFdsFailed();
            markFail(transaction, externalId);
            webhookService.saveFdsFailed(paymentIntent);
        }
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, toResponse(paymentIntent));
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse completeFds(Long transactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markFdsReady();
        transaction.markSucceeded(externalId);
        webhookService.saveReadyForConfirm(paymentIntent);
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: completeFds + idempotent complete.
    // request phase가 종료되는 지점 — 동기 흐름 / inquiry / FdsScheduler가 공유한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentApiResult completeFdsAndComplete(Long transactionId, String externalId, String idempotentKey) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() == TransactionStatus.REQUESTED || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            paymentIntent.markFdsReady();
            transaction.markSucceeded(externalId);
            webhookService.saveReadyForConfirm(paymentIntent);
        }
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_REQUEST, toResponse(paymentIntent));
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse failApprove(Long transactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markApproveFailed();
        markFail(transaction, externalId);
        webhookService.saveApproveFailed(paymentIntent);
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: failApprove + idempotent complete.
    // confirm phase가 종료되는 지점 — 동기 흐름과 inquiry 복구 경로가 공유한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentApiResult failApproveAndComplete(Long transactionId, String externalId, String idempotentKey) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() == TransactionStatus.REQUESTED || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            paymentIntent.markApproveFailed();
            markFail(transaction, externalId);
            webhookService.saveApproveFailed(paymentIntent);
        }
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, toResponse(paymentIntent));
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse completeApprove(Long approveTransactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(approveTransactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markDone(externalId);
        transaction.markSucceeded(externalId);
        webhookService.savePaymentComplete(paymentIntent);
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: completeApprove + idempotent complete.
    // confirm phase가 종료되는 지점 — 동기 흐름과 inquiry 복구 경로가 공유한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentApiResult completeApproveAndComplete(Long approveTransactionId, String externalId, String idempotentKey) {
        PaymentTransaction transaction = getTransaction(approveTransactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() == TransactionStatus.REQUESTED || transaction.getStatus() == TransactionStatus.UNKNOWN) {
            paymentIntent.markDone(externalId);
            transaction.markSucceeded(externalId);
            webhookService.savePaymentComplete(paymentIntent);
        }
        return completeIdempotentRequest(idempotentKey, IdempotencyOperation.PAYMENT_CONFIRM, toResponse(paymentIntent));
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse unknownApprove(Long transactionId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markApproveUnknown();
        transaction.markUnknown();
        return toResponse(paymentIntent);
    }

    // 병합 트랜잭션: PaymentIntent 조회+상태검증 + idempotency PROCESSING insert + PaymentIntent/CAPTURE TX 갱신.
    // FDS_READY가 아니면 idempotency key를 만들지 않고 PaymentValidationException(422)만 던진다.
    // 유니크 제약 위반 시 DataIntegrityViolationException이 전파되며 전체 롤백 — 호출부에서 기존 키를 재조회한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public ApproveRequestContext createApproveRequestWithIdempotency(String paymentKey) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));

        if (paymentIntent.getStatus() != PaymentIntentStatus.FDS_READY) {
            throw new PaymentValidationException(422, "Payment not confirmable: status is " + paymentIntent.getStatus());
        }

        String idempotentKey = IdempotentKeys.paymentConfirm(paymentIntent.getMerchantId(), paymentKey);
        String requestHash = DigestUtils.sha256Hex(idempotentKey);

        IdempotencyKey key = IdempotencyKey.builder()
                .idempotentKey(idempotentKey)
                .operation(IdempotencyOperation.PAYMENT_CONFIRM)
                .requestHash(requestHash)
                .status(IdempotentStatus.PROCESSING)
                .build();
        idempotencyKeyRepository.saveAndFlush(key);

        paymentIntent.markApproveRequested();

        PaymentTransaction authTransaction = paymentTransactionRepository
                .findByPaymentIntentAndTypeAndStatus(
                        paymentIntent,
                        TransactionType.AUTH,
                        TransactionStatus.SUCCEEDED
                )
                .orElseThrow(() -> new IllegalStateException("Succeeded auth transaction not found"));

        PaymentTransaction approveTransaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.APPROVE,
                paymentIntent.getAmount()
        );

        paymentTransactionRepository.save(approveTransaction);

        return new ApproveRequestContext(
                paymentIntent.getId(),
                approveTransaction.getId(),
                authTransaction.getExternalId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                approveTransaction.getCardRequestRef(),
                paymentIntent.getCardCompany()
        );
    }

    @Transactional
    public PaymentIntent getPaymentIntent(String paymentKey) {
        return paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));
    }

    private PaymentApiResult completeIdempotentRequest(String idempotentKey, IdempotencyOperation operation, PaymentResponse response) {
        String responseBody = objectMapper.writeValueAsString(response);
        idempotentService.complete(idempotentKey, operation, 200, responseBody);
        return new PaymentApiResult(200, responseBody);
    }

    private PaymentTransaction getTransaction(Long transactionId) {
        return paymentTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
    }

    private PaymentResponse toResponse(PaymentIntent paymentIntent) {
        return new PaymentResponse(
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getStatus(),
                paymentIntent.getAmount(),
                paymentIntent.getAuthenticatedAt()
        );
    }

    private void markFail(PaymentTransaction transaction, String externalId) {
        if (externalId == null) {
            transaction.markFailWithoutResponse();
            return;
        }

        transaction.markFail(externalId);
    }
}
