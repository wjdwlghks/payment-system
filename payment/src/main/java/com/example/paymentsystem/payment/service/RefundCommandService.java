package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.RefundRequest;
import com.example.paymentsystem.payment.dto.RefundRequestContext;
import com.example.paymentsystem.payment.dto.RefundResponse;
import com.example.paymentsystem.payment.exception.RefundValidationException;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.example.paymentsystem.payment.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RefundCommandService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;
    private final EntityManager entityManager;

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public RefundRequestContext createRefundRequest(RefundRequest request) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(request.paymentKey())
                .orElseThrow(() -> new RefundValidationException(404, "Payment intent not found: " + request.paymentKey()));
        if (paymentIntent.getStatus() != PaymentIntentStatus.DONE &&
            paymentIntent.getStatus() != PaymentIntentStatus.PARTIALLY_REFUNDED) {
            throw new RefundValidationException(409, "Payment is not in DONE or PARTIALLY_REFUNDED state");
        }

        entityManager.lock(paymentIntent, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        List<Refund> refunds = refundRepository.findByPaymentIntent(paymentIntent);
        Long refundAmount = 0L;
        for (Refund refund : refunds) {
            if (refund.getStatus() != RefundStatus.FAIL) {
                refundAmount += refund.getAmount();
            }
        }

        if (paymentIntent.getAmount() - refundAmount < request.amount()) {
            throw new RefundValidationException(409, "Refund amount exceeds refundable amount");
        }

        PaymentTransaction refundTx = new PaymentTransaction(
                paymentIntent,
                TransactionType.REFUND,
                request.amount(),
                request.refundKey()
        );

        PaymentTransaction savedTx = paymentTransactionRepository.save(refundTx);

        Refund refund = new Refund(
                paymentIntent,
                savedTx.getId(),
                request.refundKey(),
                request.amount()
        );

        refundRepository.save(refund);

        return new RefundRequestContext(
                refundTx.getId(),
                request.paymentKey(),
                request.refundKey(),
                paymentIntent.getCaptureId(),
                request.amount(),
                refundTx.getCardRequestRef()
        );
    }

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public RefundResponse completeRefund(Long txId, String refundKey, Long refundedAmount, String externalId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = getTransaction(txId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        Refund refund = getRefund(refundKey);

        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(refundKey, refund.getStatus(), refund.getAmount());
        }

        paymentIntent.addRefundedAmount(refundedAmount);
        if (paymentIntent.getRefundedAmount().equals(paymentIntent.getAmount())) {
            paymentIntent.markRefunded();
        } else {
            paymentIntent.markPartiallyRefunded();
        }
        refund.markSucceeded(externalId);
        transaction.markSucceeded(externalId);

        webhookService.saveRefundSucceeded(refund);
        ledgerService.postRefund(txId, sourceType);

        return toResponse(refundKey, RefundStatus.SUCCEEDED, refund.getAmount());
    }

    @Transactional
    public RefundResponse unknownRefund(Long txId, String refundKey) {
        PaymentTransaction transaction = getTransaction(txId);
        Refund refund = getRefund(refundKey);

        if (transaction.getStatus() != TransactionStatus.REQUESTED) {
            return toResponse(refundKey, refund.getStatus(), refund.getAmount());
        }

        refund.markUnknown();
        transaction.markUnknown();

        return toResponse(refundKey, RefundStatus.UNKNOWN, refund.getAmount());
    }

    @Transactional
    public RefundResponse failRefund(Long txId, String refundKey, String externalId) {
        PaymentTransaction transaction = getTransaction(txId);
        Refund refund = getRefund(refundKey);

        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(refundKey, refund.getStatus(), refund.getAmount());
        }

        refund.markFail(externalId);
        transaction.markFail(externalId);

        webhookService.saveRefundFailed(refund);

        return toResponse(refundKey, RefundStatus.FAIL, refund.getAmount());
    }

    private RefundResponse toResponse(String refundKey, RefundStatus status, Long amount) {
        return new RefundResponse(refundKey, status, amount);
    }

    private PaymentTransaction getTransaction(Long transactionId) {
        return paymentTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
    }

    private Refund getRefund(String refundKey) {
        return refundRepository.findByRefundKey(refundKey)
                .orElseThrow(() -> new IllegalArgumentException("Refund not found: " + refundKey));
    }
}
