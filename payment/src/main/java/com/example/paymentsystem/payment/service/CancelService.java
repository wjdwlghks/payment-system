package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.CancelRequest;
import com.example.paymentsystem.payment.dto.CancelResponse;
import com.example.paymentsystem.payment.dto.RefundRequest;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelService {

    private final PaymentCommandService paymentCommandService;
    private final RefundService refundService;
    private final CardClient cardClient;
    private final PaymentTransactionRepository paymentTransactionRepository;

    public CancelResponse cancel(String paymentKey, CancelRequest request) {
        PaymentIntent intent = paymentCommandService.getPaymentIntent(paymentKey);
        PaymentIntentStatus status = intent.getStatus();

        // AUTH_READY or FDS_READY = auth done, capture not yet started → 승인취소
        if (status == PaymentIntentStatus.AUTH_READY || status == PaymentIntentStatus.FDS_READY) {
            return cancelAuth(intent);
        }
        if (status == PaymentIntentStatus.CANCELLED) {
            return CancelResponse.authCancelled(paymentKey);
        }
        if (status == PaymentIntentStatus.DONE || status == PaymentIntentStatus.PARTIALLY_REFUNDED) {
            return cancelCapture(paymentKey, request);
        }
        throw new IllegalStateException("Cannot cancel payment in status: " + status);
    }

    private CancelResponse cancelAuth(PaymentIntent intent) {
        var authTx = paymentTransactionRepository
                .findByPaymentIntentAndTypeAndStatus(intent, TransactionType.AUTH, TransactionStatus.SUCCEEDED)
                .orElseThrow(() -> new IllegalStateException("No SUCCEEDED AUTH tx found"));

        try {
            cardClient.voidAuth(intent.getCardCompany(), authTx.getExternalId());
        } catch (Exception e) {
            log.warn("Card void auth failed for paymentKey={}, proceeding with local cancel: {}",
                    intent.getPaymentKey(), e.getMessage());
        }

        paymentCommandService.cancelAuth(intent.getPaymentKey());
        return CancelResponse.authCancelled(intent.getPaymentKey());
    }

    private CancelResponse cancelCapture(String paymentKey, CancelRequest request) {
        if (request.amount() == null) {
            throw new IllegalArgumentException("amount is required for capture cancellation");
        }
        refundService.refund(new RefundRequest(paymentKey, request.cancelKey(), request.amount()));
        return CancelResponse.captureCancelled(paymentKey, request.amount());
    }
}
