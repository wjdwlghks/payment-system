package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.AuthInquiryResponse;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.client.card.ApproveInquiryResponse;
import com.example.paymentsystem.payment.client.card.CaptureInquiryResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.client.fds.FdsInquiryResponse;
import com.example.paymentsystem.payment.component.RecoveryCounter;
import com.example.paymentsystem.payment.domain.CardCompany;
import com.example.paymentsystem.payment.domain.IdempotentKeys;
import com.example.paymentsystem.payment.domain.LedgerSourceType;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InquiryService {

    private static final Duration STALE_REQUESTED_THRESHOLD = Duration.ofSeconds(60);

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CardClient cardClient;
    private final FdsClient fdsClient;
    private final ExternalCallExecutor externalCallExecutor;
    private final PaymentCommandService paymentCommandService;
    private final CaptureCommandService captureCommandService;
    private final RecoveryCounter recoveryCounter;

    @Transactional(readOnly = true)
    public List<PaymentTransaction> getUnknowns() {
        return paymentTransactionRepository
                .findTop300WithIntentByStatusOrderByUpdatedAtAsc(TransactionStatus.UNKNOWN);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> getStaleRequested() {
        Instant threshold = Instant.now().minus(STALE_REQUESTED_THRESHOLD);
        return paymentTransactionRepository
                .findTop300WithIntentByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        TransactionStatus.REQUESTED,
                        threshold
                );
    }

    public void inquiryAuth(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("auth");
        CardCompany company = transaction.getPaymentIntent().getCardCompany();
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryAuth(company, cardRequestRef),
                response -> handleAuthInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failAuthAndComplete(
                        transaction.getId(), null, requestKey(transaction))
        );
    }

    public void inquiryFds(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("fds");
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> fdsClient.inquiry(cardRequestRef),
                response -> handleFdsInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failFdsAndComplete(
                        transaction.getId(), null, requestKey(transaction))
        );
    }

    public void inquiryApprove(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("approve");
        CardCompany company = transaction.getPaymentIntent().getCardCompany();
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryApprove(company, cardRequestRef),
                response -> handleApproveInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failApproveAndComplete(
                        transaction.getId(), null, approveKey(transaction))
        );
    }

    private void handleAuthInquiry(PaymentTransaction transaction, AuthInquiryResponse response) {
        recoveryCounter.incrementInquiryResult("auth", response.status());
        String idempotentKey = requestKey(transaction);
        switch (response.status()) {
            // AUTH 성공은 request phase의 종료가 아니다(FDS가 남음) — 멱등키는 PROCESSING을 유지하고
            // FdsScheduler가 FDS를 이어서 실행한 뒤 그 시점에 완결한다.
            case "success" -> paymentCommandService.completeAuth(
                    transaction.getId(),
                    response.externalId(),
                    response.authenticatedAt()
            );
            case "failed" -> paymentCommandService.failAuthAndComplete(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "not_found" -> paymentCommandService.failAuthAndComplete(
                    transaction.getId(), null, idempotentKey);
            case "in_progress" -> {}
        }
    }

    private void handleFdsInquiry(PaymentTransaction transaction, FdsInquiryResponse response) {
        recoveryCounter.incrementInquiryResult("fds", response.status());
        String idempotentKey = requestKey(transaction);
        switch (response.status()) {
            case "success" -> paymentCommandService.completeFdsAndComplete(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "failed" -> paymentCommandService.failFdsAndComplete(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "not_found" -> paymentCommandService.failFdsAndComplete(
                    transaction.getId(), null, idempotentKey);
            case "in_progress" -> {}
        }
    }

    // 매입은 배치라 사용자 멱등키가 없다 — 상태만 확정하면 된다.
    public void inquiryCapture(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("capture");
        CardCompany company = transaction.getPaymentIntent().getCardCompany();
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryCapture(company, cardRequestRef),
                response -> handleCaptureInquiry(transaction, response),
                () -> {},
                () -> captureCommandService.failCapture(transaction.getId(), null)
        );
    }

    private void handleCaptureInquiry(PaymentTransaction transaction, CaptureInquiryResponse response) {
        recoveryCounter.incrementInquiryResult("capture", response.status());
        switch (response.status()) {
            case "success" -> captureCommandService.completeCapture(
                    transaction.getId(), response.externalId(), LedgerSourceType.PAYMENT_TRANSACTION);
            case "failed" -> captureCommandService.failCapture(transaction.getId(), response.externalId());
            case "not_found" -> captureCommandService.failCapture(transaction.getId(), null);
            case "in_progress" -> {}
        }
    }

    private void handleApproveInquiry(PaymentTransaction transaction, ApproveInquiryResponse response) {
        recoveryCounter.incrementInquiryResult("approve", response.status());
        String idempotentKey = approveKey(transaction);
        switch (response.status()) {
            case "success" -> paymentCommandService.completeApproveAndComplete(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "failed" -> paymentCommandService.failApproveAndComplete(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "not_found" -> paymentCommandService.failApproveAndComplete(
                    transaction.getId(), null, idempotentKey);
            case "in_progress" -> {}
        }
    }

    // 복구 경로가 완결해야 할 멱등키를 PaymentIntent로부터 재구성한다.
    private String requestKey(PaymentTransaction transaction) {
        PaymentIntent intent = transaction.getPaymentIntent();
        return IdempotentKeys.paymentRequest(intent.getMerchantId(), intent.getOrderId());
    }

    private String approveKey(PaymentTransaction transaction) {
        PaymentIntent intent = transaction.getPaymentIntent();
        return IdempotentKeys.paymentApprove(intent.getMerchantId(), intent.getPaymentKey());
    }
}
