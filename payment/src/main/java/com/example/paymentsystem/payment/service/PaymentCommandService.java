package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.*;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
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
                transaction.getIdempotentKey()
        );
    }

    @Transactional
    public PaymentResponse completeAuth(Long transactionId, String externalId, Instant authorizedAt) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markAuthReady(authorizedAt);
            transaction.markSucceeded(externalId);
        }, webhookService::saveAuthReady);
    }

    @Transactional
    public PaymentResponse failAuth(Long transactionId, String externalId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markAuthFailed();
            markFail(transaction, externalId);
        }, webhookService::saveAuthFailed);
    }

    @Transactional
    public PaymentResponse unknownAuth(Long transactionId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markAuthUnknown();
            transaction.markUnknown();
        });
    }

    @Transactional
    public PaymentResponse unknownFds(Long transactionId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markFdsUnknown();
            transaction.markUnknown();
        });
    }

    @Transactional
    public PaymentResponse unknownCapture(Long transactionId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markCaptureUnknown();
            transaction.markUnknown();
        });
    }

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

    @Transactional
    public PaymentResponse failFds(Long transactionId, String externalId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markFdsFailed();
            markFail(transaction, externalId);
        }, webhookService::saveFdsFailed);
    }

    @Transactional
    public PaymentResponse completeFds(Long transactionId, String externalId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markFdsReady();
            transaction.markSucceeded(externalId);
        });
    }

    @Transactional
    public PaymentResponse failCapture(Long transactionId, String externalId) {
        return update(transactionId, (paymentIntent, transaction) -> {
            paymentIntent.markCaptureFailed();
            markFail(transaction, externalId);
        }, webhookService::saveCaptureFailed);
    }

    @Transactional
    public PaymentResponse completeCapture(Long captureTransactionId, String externalId) {
        PaymentResponse response = update(captureTransactionId, (paymentIntent, transaction) -> {
            paymentIntent.markDone(externalId);
            transaction.markSucceeded(externalId);
        }, webhookService::savePaymentComplete);

        ledgerService.postCapture(captureTransactionId);

        return response;
    }

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
                captureIdempotentKey
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

    private PaymentResponse update(
            Long transactionId,
            BiConsumer<PaymentIntent, PaymentTransaction> updater
    ) {
        return update(transactionId, updater, paymentIntent -> {});
    }

    private PaymentResponse update(
            Long transactionId,
            BiConsumer<PaymentIntent, PaymentTransaction> updater,
            Consumer<PaymentIntent> afterUpdate
    ) {
        PaymentTransaction transaction = getTransaction(transactionId);
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        updater.accept(paymentIntent, transaction);
        afterUpdate.accept(paymentIntent);
        return toResponse(paymentIntent);
    }

    private void markFail(PaymentTransaction transaction, String externalId) {
        if (externalId == null) {
            transaction.markFailWithoutResponse();
            return;
        }

        transaction.markFail(externalId);
    }
}
