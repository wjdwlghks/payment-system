package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.AuthInquiryResponse;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.client.card.CaptureInquiryResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.client.fds.FdsInquiryResponse;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InquiryService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CardClient cardClient;
    private final FdsClient fdsClient;
    private final ExternalCallExecutor externalCallExecutor;
    private final PaymentCommandService paymentCommandService;

    @Transactional(readOnly = true)
    public List<PaymentTransaction> getUnknowns() {
        return paymentTransactionRepository
                .findTop3ByStatusOrderByUpdatedAtAsc(TransactionStatus.UNKNOWN);
    }

    public void inquiryAuth(PaymentTransaction transaction) {
        String authIdempotentKey = transaction.getIdempotentKey();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryAuth(authIdempotentKey),
                response -> handleAuthInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failAuth(transaction.getId(), null)
        );
    }

    public void inquiryFds(PaymentTransaction transaction) {
        String fdsIdempotencyKey = transaction.getIdempotentKey();
        externalCallExecutor.executeVoid(
                () -> fdsClient.inquiry(fdsIdempotencyKey),
                response -> handleFdsInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failFds(transaction.getId(), null)
        );
    }

    public void inquiryCapture(PaymentTransaction transaction) {
        String captureIdempotentKey = transaction.getIdempotentKey();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryCapture(captureIdempotentKey),
                response -> handleCaptureInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failCapture(transaction.getId(), null)
        );
    }

    private void handleAuthInquiry(PaymentTransaction transaction, AuthInquiryResponse response) {
        switch (response.status()) {
            case "success" -> paymentCommandService.completeAuth(
                    transaction.getId(),
                    response.externalId(),
                    response.authorizedAt()
            );
            case "failed" -> paymentCommandService.failAuth(transaction.getId(), response.externalId());
            case "not_found" -> paymentCommandService.failAuth(transaction.getId(), null);
            case "in_progress" -> {}
        }
    }

    private void handleFdsInquiry(PaymentTransaction transaction, FdsInquiryResponse response) {
        switch (response.status()) {
            case "success" -> paymentCommandService.completeFds(transaction.getId(), response.externalId());
            case "failed" -> paymentCommandService.failFds(transaction.getId(), response.externalId());
            case "not_found" -> paymentCommandService.failFds(transaction.getId(), null);
            case "in_progress" -> {}
        }
    }

    private void handleCaptureInquiry(PaymentTransaction transaction, CaptureInquiryResponse response) {
        switch (response.status()) {
            case "success" -> paymentCommandService.completeCapture(transaction.getId(), response.externalId());
            case "failed" -> paymentCommandService.failCapture(transaction.getId(), response.externalId());
            case "not_found" -> paymentCommandService.failCapture(transaction.getId(), null);
            case "in_progress" -> {}
        }
    }
}
