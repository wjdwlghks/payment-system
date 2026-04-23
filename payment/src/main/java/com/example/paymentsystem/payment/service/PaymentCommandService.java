package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.CardAuthResponse;
import com.example.paymentsystem.payment.client.CardCaptureResponse;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.PaymentRequest;
import com.example.paymentsystem.payment.dto.PaymentResponse;
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
    public AuthRequestContext createAuthRequest(PaymentRequest request, String idempotencyKey) {
        PaymentIntent paymentIntent = new PaymentIntent(
                UUID.randomUUID().toString(),
                request.orderId(),
                request.merchantId(),
                request.amount()
        );
        paymentIntentRepository.save(paymentIntent);

        PaymentTransaction transaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.AUTH,
                request.amount(),
                idempotencyKey
        );
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
    public PaymentResponse completeAuth(Long paymentIntentId, Long transactionId, CardAuthResponse cardResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentIntentId);
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
        PaymentTransaction transaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.FDS,
                paymentIntent.getAmount(),
                null
        );
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
    public PaymentResponse failFds(Long paymentIntentId, Long transactionId, FdsCheckResponse fdsResponse) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentIntentId);
        PaymentTransaction transaction = getTransaction(transactionId);

        transaction.markFail(fdsResponse.externalId());
        paymentIntent.markFdsFailed();

        return toResponse(paymentIntent);
    }

    @Transactional
    public CaptureRequestContext completeFdsAndCreateCaptureRequest(
            Long paymentIntentId,
            Long fdsTransactionId,
            FdsCheckResponse fdsResponse,
            String idempotencyKey
    ) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentIntentId);
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

        PaymentTransaction captureTransaction = new PaymentTransaction(
                paymentIntent,
                TransactionType.CAPTURE,
                paymentIntent.getAmount(),
                idempotencyKey
        );
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
            Long paymentIntentId,
            Long captureTransactionId,
            CardCaptureResponse captureResponse
    ) {
        PaymentIntent paymentIntent = getPaymentIntent(paymentIntentId);
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

    private PaymentIntent getPaymentIntent(Long paymentIntentId) {
        return paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentIntentId));
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

