package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.*;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final WebhookService webhookService;
    private final AccountService accountService;
    private final LedgerService ledgerService;


    @Transactional
    public AuthRequestContext createAuthRequest(PaymentRequest request) {
        PaymentIntent paymentIntent = new PaymentIntent(
                UUID.randomUUID().toString(),
                request.orderId(),
                request.merchantId(),
                request.amount()
        );
        paymentIntentRepository.save(paymentIntent);

        String authIdempotentKey = paymentIntent.getPaymentKey() + ":auth";
        PaymentTransaction transaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.AUTH,
                request.amount(),
                authIdempotentKey
        );

        paymentTransactionRepository.save(transaction);

        return new AuthRequestContext(
                paymentIntent.getId(),
                transaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                transaction.getIdempotentKey(),
                transaction.getCardRequestRef()
        );
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public PaymentResponse completeAuth(Long transactionId, String externalId, Instant authorizedAt) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markAuthReady(authorizedAt);
        transaction.markSucceeded(externalId);
        webhookService.saveAuthReady(paymentIntent);
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
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

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
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

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
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

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public PaymentResponse unknownCapture(Long transactionId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markCaptureUnknown();
        transaction.markUnknown();
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public FdsRequestContext createFdsRequest(String paymentKey) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));

        paymentIntent.markFdsRequested();
        PaymentTransaction transaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.FDS,
                paymentIntent.getAmount(),
                paymentIntent.getPaymentKey() + ":fds"
        );

        paymentTransactionRepository.save(transaction);

        return new FdsRequestContext(
                paymentIntent.getId(),
                transaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount(),
                transaction.getIdempotentKey()
        );
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
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

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
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
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public PaymentResponse failCapture(Long transactionId, String externalId) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markCaptureFailed();
        markFail(transaction, externalId);
        webhookService.saveCaptureFailed(paymentIntent);
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public PaymentResponse completeCapture(Long captureTransactionId, String externalId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = getTransaction(captureTransactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        if (transaction.getStatus() != TransactionStatus.REQUESTED
                && transaction.getStatus() != TransactionStatus.UNKNOWN) {
            return toResponse(paymentIntent);
        }
        paymentIntent.markDone(externalId);
        transaction.markSucceeded(externalId);
        webhookService.savePaymentComplete(paymentIntent);
        ledgerService.postCapture(captureTransactionId, sourceType);
        return toResponse(paymentIntent);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public CaptureRequestContext completeFdsAndCreateCaptureRequest(
            Long fdsTransactionId,
            String externalId
    ) {
        PaymentTransaction fdsTransaction = getTransaction(fdsTransactionId);
        PaymentIntent paymentIntent = fdsTransaction.getPaymentIntent();

        fdsTransaction.markSucceeded(externalId);

        return createCaptureRequest(paymentIntent.getPaymentKey());
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public CaptureRequestContext createCaptureRequest(String paymentKey) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        paymentIntent.markCaptureRequested();

        String captureIdempotentKey = paymentIntent.getPaymentKey() + ":capture";
        PaymentTransaction authTransaction = paymentTransactionRepository
                .findByPaymentIntentAndTypeAndStatus(
                        paymentIntent,
                        TransactionType.AUTH,
                        TransactionStatus.SUCCEEDED
                )
                .orElseThrow(() -> new IllegalStateException("Succeeded auth transaction not found"));

        PaymentTransaction captureTransaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.CAPTURE,
                paymentIntent.getAmount(),
                captureIdempotentKey
        );

        paymentTransactionRepository.save(captureTransaction);

        return new CaptureRequestContext(
                paymentIntent.getId(),
                captureTransaction.getId(),
                authTransaction.getExternalId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getAmount(),
                captureIdempotentKey,
                captureTransaction.getCardRequestRef()
        );
    }

    @Transactional
    public PaymentIntent getPaymentIntent(String paymentKey) {
        return paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));
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
                paymentIntent.getAuthorizedAt()
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
