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

        PaymentTransaction transaction = PaymentTransaction.builder()
                .paymentIntent(paymentIntent)
                .type(TransactionType.AUTH)
                .status(TransactionStatus.REQUESTED)
                .amount(request.amount())
                .build();

        paymentTransactionRepository.save(transaction);

        return new AuthRequestContext(
                paymentIntent.getId(),
                transaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount()
        );
    }

    @Transactional
    public PaymentResponse completeAuth(String paymentKey, Long transactionId, CardAuthResponse cardResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction transaction = getTransaction(transactionId);

        if (cardResponse.success()) {
            transaction.markSucceeded(cardResponse.externalId());
            paymentIntent.markAuthReady(cardResponse.authorizedAt());
        } else {
            transaction.markFail(cardResponse.externalId());
            paymentIntent.markAuthFailed();
        }

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
                .build();

        paymentTransactionRepository.save(transaction);

        return new FdsRequestContext(
                paymentIntent.getId(),
                transaction.getId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getMerchantId(),
                paymentIntent.getAmount()
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
    public CaptureRequestContext completeFdsAndCreateCaptureRequest(
            String paymentKey,
            Long fdsTransactionId,
            FdsCheckResponse fdsResponse
    ) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentKey);
        PaymentTransaction fdsTransaction = getTransaction(fdsTransactionId);

        fdsTransaction.markSucceeded(fdsResponse.externalId());
        paymentIntent.markCaptureRequested();

        PaymentTransaction authTransaction = paymentTransactionRepository
                .findFirstByPaymentIntentIdAndTypeAndStatusOrderByIdDesc(
                        paymentIntent.getId(),
                        TransactionType.AUTH,
                        TransactionStatus.SUCCEEDED
                )
                .orElseThrow(() -> new IllegalStateException("Succeeded auth transaction not found"));

        PaymentTransaction captureTransaction = PaymentTransaction.builder()
                .paymentIntent(paymentIntent)
                .type(TransactionType.CAPTURE)
                .status(TransactionStatus.REQUESTED)
                .amount(paymentIntent.getAmount())
                .build();

        paymentTransactionRepository.save(captureTransaction);

        return new CaptureRequestContext(
                paymentIntent.getId(),
                captureTransaction.getId(),
                authTransaction.getExternalId(),
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getAmount()
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

        if (captureResponse.success()) {
            captureTransaction.markSucceeded(captureResponse.externalId());
            paymentIntent.markDone();
        } else {
            captureTransaction.markFail(captureResponse.externalId());
            paymentIntent.markCaptureFailed();
        }

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
