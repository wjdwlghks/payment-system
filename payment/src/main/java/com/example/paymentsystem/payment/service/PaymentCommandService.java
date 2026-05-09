package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.CardAuthResponse;
import com.example.paymentsystem.payment.client.CardCaptureResponse;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.*;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

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
        PaymentTransaction transaction = PaymentTransaction.builder()
                .paymentIntent(paymentIntent)
                .type(TransactionType.AUTH)
                .status(TransactionStatus.REQUESTED)
                .amount(request.amount())
                .idempotentKey(authIdempotentKey)
                .build();

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
    public PaymentResponse completeAuth(String paymentKey, Long transactionId, CardAuthResponse cardResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        transaction.markSucceeded(cardResponse.externalId());
        paymentIntent.markAuthReady(cardResponse.authorizedAt());

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse failAuth(String paymentKey, Long transactionId) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        paymentIntent.markAuthFailed();
        transaction.markFailWithoutResponse();

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse failAuth(String paymentKey, Long transactionId, CardAuthResponse cardResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        paymentIntent.markAuthFailed();
        transaction.markFail(cardResponse.externalId());

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse unknownAuth(String paymentKey,  Long transactionId) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        paymentIntent.markAuthUnknown();
        transaction.markUnknown();

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse unknownFds(String paymentKey, Long transactionId) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        paymentIntent.markFdsUnknown();
        transaction.markUnknown();

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse unknownCapture(String paymentKey, Long transcationId) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transcationId);

        paymentIntent.markCaptureUnknown();
        transaction.markUnknown();

        return toResponse(paymentIntent);
    }

    @Transactional
    public FdsRequestContext createFdsRequest(String paymentKey) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));

        paymentIntent.markFdsRequested();
        PaymentTransaction transaction = PaymentTransaction.builder()
                .paymentIntent(paymentIntent)
                .type(TransactionType.FDS)
                .amount(paymentIntent.getAmount())
                .status(TransactionStatus.REQUESTED)
                .idempotentKey(paymentIntent.getPaymentKey() + ":fds")
                .build();

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
    public PaymentResponse failFds(String paymentKey, Long transactionId, FdsCheckResponse fdsResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        transaction.markFail(fdsResponse.externalId());
        paymentIntent.markFdsFailed();

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse failFds(String paymentKey, Long transactionId) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        transaction.markFailWithoutResponse();
        paymentIntent.markFdsFailed();

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse failCapture(String paymentKey, Long transactionId, CardCaptureResponse captureResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        paymentIntent.markCaptureFailed();
        transaction.markFail(captureResponse.externalId());

        return toResponse(paymentIntent);
    }

    @Transactional
    public PaymentResponse failCapture(String paymentKey, Long transactionId) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        paymentIntent.markCaptureFailed();
        transaction.markFailWithoutResponse();

        return toResponse(paymentIntent);
    }

    @Transactional
    public CaptureRequestContext completeFdsAndCreateCaptureRequest(
            String paymentKey,
            Long fdsTransactionId,
            FdsCheckResponse fdsResponse
    ) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction fdsTransaction = getTransaction(fdsTransactionId);

        fdsTransaction.markSucceeded(fdsResponse.externalId());
        paymentIntent.markCaptureRequested();

        String captureIdempotentKey = paymentIntent.getPaymentKey() + ":capture";
        PaymentTransaction authTransaction = paymentTransactionRepository
                .findByPaymentIntentAndTypeAndStatus(
                        paymentIntent,
                        TransactionType.AUTH,
                        TransactionStatus.SUCCEEDED
                )
                .orElseThrow(() -> new IllegalStateException("Succeeded auth transaction not found"));

        PaymentTransaction captureTransaction = PaymentTransaction.builder()
                .paymentIntent(paymentIntent)
                .type(TransactionType.CAPTURE)
                .status(TransactionStatus.REQUESTED)
                .amount(paymentIntent.getAmount())
                .idempotentKey(captureIdempotentKey)
                .build();

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
    public PaymentResponse completeCapture(
            String paymentKey,
            Long captureTransactionId,
            CardCaptureResponse captureResponse
    ) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction captureTransaction = getTransaction(captureTransactionId);


        captureTransaction.markSucceeded(captureResponse.externalId());
        paymentIntent.markDone();

        return toResponse(paymentIntent);
    }

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

}
