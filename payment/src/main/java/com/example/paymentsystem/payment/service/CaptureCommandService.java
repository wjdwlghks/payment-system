package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.CaptureRequestContext;
import com.example.paymentsystem.payment.dto.CaptureRunResponse;
import com.example.paymentsystem.payment.repository.CaptureBatchItemRepository;
import com.example.paymentsystem.payment.repository.CaptureBatchRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매입(CAPTURE) 상태 전이와 원장 기표.
 * 매입은 admin 배치라 사용자 멱등키가 없다 — 중복 매입은 CAPTURE tx의
 * payment_intent_id UNIQUE 제약으로 막는다.
 */
@Service
@RequiredArgsConstructor
public class CaptureCommandService {

    private static final ZoneId BATCH_CODE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BATCH_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CaptureBatchRepository captureBatchRepository;
    private final CaptureBatchItemRepository captureBatchItemRepository;
    private final LedgerService ledgerService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaptureBatch createBatch() {
        return captureBatchRepository.save(new CaptureBatch(generateBatchCode()));
    }

    @Transactional(readOnly = true)
    public List<Long> findTargets(int limit) {
        return paymentIntentRepository.findCaptureTargetIds(PageRequest.of(0, limit));
    }

    // 매입 tx 생성은 건별 커밋 — 중간에 죽어도 이미 만든 tx는 남아 stale REQUESTED 복구가 집어간다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaptureRequestContext createCaptureRequest(Long intentId, Long batchId) {
        PaymentIntent intent = paymentIntentRepository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + intentId));
        CaptureBatch batch = captureBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Capture batch not found: " + batchId));

        PaymentTransaction captureTx = paymentTransactionRepository.save(
                new PaymentTransaction(intent, TransactionType.CAPTURE, intent.getAmount())
        );
        captureBatchItemRepository.save(new CaptureBatchItem(batch, captureTx, intent.getAmount()));

        return new CaptureRequestContext(
                captureTx.getId(),
                intent.getApprovalId(),
                intent.getAmount(),
                captureTx.getCardRequestRef(),
                intent.getCardCompany()
        );
    }

    // 매입 확정 시점에 원장을 기표한다 (승인 시점이 아니라).
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeCapture(Long captureTransactionId, String externalId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return;
        }
        transaction.markSucceeded(externalId);
        ledgerService.postCapture(captureTransactionId, sourceType);
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failCapture(Long captureTransactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return;
        }
        if (externalId == null) {
            transaction.markFailWithoutResponse();
        } else {
            transaction.markFail(externalId);
        }
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unknownCapture(Long captureTransactionId) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        if (transaction.getStatus() != TransactionStatus.REQUESTED) {
            return;
        }
        transaction.markUnknown();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaptureRunResponse completeBatch(Long batchId) {
        CaptureBatch batch = captureBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Capture batch not found: " + batchId));
        List<PaymentTransaction> txs = captureBatchItemRepository.findTransactionsByBatchId(batchId);

        int succeeded = 0, failed = 0, unknown = 0;
        long succeededAmount = 0L;
        for (PaymentTransaction tx : txs) {
            switch (tx.getStatus()) {
                case SUCCEEDED -> { succeeded++; succeededAmount += tx.getAmount(); }
                case FAIL -> failed++;
                default -> unknown++;
            }
        }
        batch.markCompleted(txs.size(), succeeded, failed, unknown, succeededAmount);

        return new CaptureRunResponse(
                batch.getId(), batch.getBatchCode(),
                txs.size(), succeeded, failed, unknown, succeededAmount
        );
    }

    private PaymentTransaction getTransaction(Long transactionId) {
        return paymentTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
    }

    private String generateBatchCode() {
        String prefix = "CAP-" + LocalDate.now(BATCH_CODE_ZONE).format(BATCH_CODE_DATE_FORMATTER) + "-";
        int next = captureBatchRepository
                .findTop1ByBatchCodeStartingWithOrderByBatchCodeDesc(prefix)
                .map(CaptureBatch::getBatchCode)
                .map(code -> code.substring(prefix.length()))
                .map(Integer::parseInt)
                .orElse(0) + 1;
        return prefix + String.format("%03d", next);
    }
}
