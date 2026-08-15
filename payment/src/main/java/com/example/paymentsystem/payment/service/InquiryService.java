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
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.dto.InquiryClaim;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import com.example.paymentsystem.payment.dto.PendingInquiry;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
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
    private final FdsExecutionService fdsExecutionService;
    private final RecoveryCounter recoveryCounter;

    /**
     * 조회 한 건을 선점한다. 아직 확정 전(REQUESTED/UNKNOWN)일 때만 값을 돌려주고,
     * 그때 <b>다음 조회 시각을 먼저 DB에 적는다</b>.
     *
     * <p>조회 전에 적는 이유는 이 시점 이후로 무슨 일이 생겨도(프로세스 종료, 예외, 큐 유실)
     * DB에 합리적인 다음 시각이 남아 sweeper가 이어받을 수 있게 하기 위해서다.
     * 그리고 큐가 재예약할 간격과 DB에 적힌 시각이 같아, 두 장치가 같은 시각을 본다.
     */
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public Optional<InquiryClaim> claimForAttempt(Long transactionId) {
        return paymentTransactionRepository.findWithIntentById(transactionId)
                .filter(PaymentTransaction::isPendingInquiry)
                .map(transaction -> {
                    Duration next = InquiryBackoff.of(transaction.getInquiryAttempts() + 1);
                    transaction.scheduleNextInquiry(Instant.now().plus(next));
                    return new InquiryClaim(transaction, next);
                });
    }

    @Transactional(readOnly = true)
    public boolean isPendingInquiry(Long transactionId) {
        return paymentTransactionRepository.findById(transactionId)
                .map(PaymentTransaction::isPendingInquiry)
                .orElse(false);
    }

    /** 타입별 조회 분기. 큐 소비자와 sweeper가 공유한다. */
    public void inquire(PaymentTransaction transaction) {
        switch (transaction.getType()) {
            case AUTH -> inquiryAuth(transaction);
            case FDS -> inquiryFds(transaction);
            case APPROVE -> inquiryApprove(transaction);
            case CAPTURE -> inquiryCapture(transaction);
        }
    }

    @Transactional(readOnly = true)
    public List<Long> findOverdueUnknownIds(Duration grace, int limit) {
        return paymentTransactionRepository.findOverdueUnknownIds(
                Instant.now().minus(grace), Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<Long> findStaleRequestedIds(int limit) {
        return paymentTransactionRepository.findStaleRequestedIds(
                Instant.now().minus(STALE_REQUESTED_THRESHOLD), Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public List<PendingInquiry> findAllPendingInquiries(int limit) {
        return paymentTransactionRepository.findAllPendingInquiries(Limit.of(limit));
    }

    private void inquiryAuth(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("auth");
        CardCompany company = transaction.getPaymentIntent().getCardCompany();
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryAuth(company, cardRequestRef),
                response -> handleAuthInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failAuth(
                        transaction.getId(), null, requestKey(transaction))
        );
    }

    private void inquiryFds(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("fds");
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> fdsClient.inquiry(cardRequestRef),
                response -> handleFdsInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failFds(
                        transaction.getId(), null, requestKey(transaction))
        );
    }

    private void inquiryApprove(PaymentTransaction transaction) {
        recoveryCounter.incrementInquiryTotal("approve");
        CardCompany company = transaction.getPaymentIntent().getCardCompany();
        String cardRequestRef = transaction.getCardRequestRef();
        externalCallExecutor.executeVoid(
                () -> cardClient.inquiryApprove(company, cardRequestRef),
                response -> handleApproveInquiry(transaction, response),
                () -> {},
                () -> paymentCommandService.failApprove(
                        transaction.getId(), null, approveKey(transaction))
        );
    }

    private void handleAuthInquiry(PaymentTransaction transaction, AuthInquiryResponse response) {
        recoveryCounter.incrementInquiryResult("auth", response.status());
        String idempotentKey = requestKey(transaction);
        switch (response.status()) {
            // AUTH 성공은 request phase의 종료가 아니다(FDS가 남음) — 멱등키는 PROCESSING을 유지한 채
            // 이 자리에서 FDS까지 이어 실행하고, FDS가 확정되는 트랜잭션에서 완결한다.
            case "success" -> completeAuthAndContinueToFds(transaction, response);
            case "failed" -> paymentCommandService.failAuth(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "not_found" -> paymentCommandService.failAuth(
                    transaction.getId(), null, idempotentKey);
            case "in_progress" -> {}
        }
    }

    /**
     * inquiry가 AUTH를 확정한 그 자리에서 FDS를 이어서 호출한다.
     *
     * <p>동기 흐름은 {@code completeAuthAndRequestFds}로 AUTH→FDS를 한 트랜잭션에 잇는데,
     * 복구 경로만 AUTHENTICATED에서 멈추고 FdsScheduler의 다음 틱을 기다리면 사용자가 스케줄러
     * 주기만큼 더 기다리게 된다. inquiry 틱과 FDS 틱이 겹쳐 쌓이는 최악 지연을 없애기 위해 여기서 잇는다.
     *
     * <p>이 인라인 호출이 어떤 이유로 실패해도 intent는 AUTHENTICATED로 남으므로
     * FdsScheduler가 안전망으로 이어받는다 — 그래서 예외를 삼키고 로그만 남긴다.
     */
    private void completeAuthAndContinueToFds(PaymentTransaction transaction, AuthInquiryResponse response) {
        PaymentResponse completed = paymentCommandService.completeAuth(
                transaction.getId(),
                response.externalId(),
                response.authenticatedAt()
        );

        // 다른 경로(동기 흐름 · FdsScheduler)가 이미 FDS 이후로 진행시켰다면 여기서 손을 뗀다.
        // markFdsRequested()의 엔티티 불변식과 같은 판정이라 서로 어긋나지 않는다.
        if (completed.status() != PaymentIntentStatus.AUTHENTICATED) {
            return;
        }

        try {
            fdsExecutionService.checkFds(transaction.getPaymentIntent());
        } catch (Exception e) {
            log.warn("Inline FDS after auth inquiry failed; FdsScheduler will pick it up. paymentKey={}",
                    transaction.getPaymentIntent().getPaymentKey(), e);
        }
    }

    private void handleFdsInquiry(PaymentTransaction transaction, FdsInquiryResponse response) {
        recoveryCounter.incrementInquiryResult("fds", response.status());
        String idempotentKey = requestKey(transaction);
        switch (response.status()) {
            case "success" -> paymentCommandService.completeFds(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "failed" -> paymentCommandService.failFds(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "not_found" -> paymentCommandService.failFds(
                    transaction.getId(), null, idempotentKey);
            case "in_progress" -> {}
        }
    }

    // 매입은 배치라 사용자 멱등키가 없다 — 상태만 확정하면 된다.
    private void inquiryCapture(PaymentTransaction transaction) {
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
            case "success" -> paymentCommandService.completeApprove(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "failed" -> paymentCommandService.failApprove(
                    transaction.getId(), response.externalId(), idempotentKey);
            case "not_found" -> paymentCommandService.failApprove(
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
