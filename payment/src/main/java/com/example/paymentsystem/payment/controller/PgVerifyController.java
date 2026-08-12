package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.WebhookOutboxStatus;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.LedgerEntryRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.example.paymentsystem.payment.repository.ReconBatchRepository;
import com.example.paymentsystem.payment.repository.WebhookOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/verify")
@RequiredArgsConstructor
public class PgVerifyController {

    private final PaymentTransactionRepository txRepository;
    private final WebhookOutboxRepository outboxRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final ReconBatchRepository reconBatchRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @GetMapping("/pg-internal")
    public PgInternalResult pgInternal() {
        long doneWithoutApprove        = txRepository.countDoneWithoutApproveSucceeded();
        long approveWithoutDone        = txRepository.countApproveSucceededWithoutDone();
        long captureWithoutApprove     = txRepository.countCaptureWithoutApprove();
        long approvedWithoutCapture    = txRepository.countApprovedWithoutCapture();
        long unknownRemaining          = txRepository.countByStatus(TransactionStatus.UNKNOWN);
        long pendingOutbox             = outboxRepository.countByStatus(WebhookOutboxStatus.PENDING);
        long reconDiscrepancy          = reconBatchRepository.sumDiscrepancyCount();
        long processingIdempotencyKeys = idempotencyKeyRepository.countByStatus(IdempotentStatus.PROCESSING);

        // approvedWithoutCapture는 매입 배치 실행 전이면 정상값이라 판정에서 뺀다
        // (pendingOutbox와 같은 취급). 매입 완주 후 0인지는 검증 스크립트가 따로 본다.
        boolean passed = doneWithoutApprove == 0 && approveWithoutDone == 0
                && captureWithoutApprove == 0
                && unknownRemaining == 0
                && reconDiscrepancy == 0 && processingIdempotencyKeys == 0;

        return new PgInternalResult(
                doneWithoutApprove, approveWithoutDone,
                captureWithoutApprove, approvedWithoutCapture,
                unknownRemaining, pendingOutbox, reconDiscrepancy,
                processingIdempotencyKeys, passed);
    }

    @GetMapping("/ledger")
    public LedgerResult ledger() {
        long unbalancedPostings = ledgerEntryRepository.countUnbalancedPostings();
        long cardReceivableBalance = accountRepository
                .findByAccountTypeAndMerchantId(AccountType.CARD_NETWORK_RECEIVABLE, Account.GLOBAL_MERCHANT_ID)
                .map(a -> {
                    long d = ledgerRepository.sumUnappliedByDirection(a.getId(), LedgerDirection.DEBIT);
                    long c = ledgerRepository.sumUnappliedByDirection(a.getId(), LedgerDirection.CREDIT);
                    return a.getBalance() + a.computeNetDelta(d, c);
                })
                .orElse(0L);

        // cardReceivableBalance는 미청산 매입이 남으면 0이 아닐 수 있어 참고값으로만 노출.
        // 정합성 판정은 복식부기 균형(unbalancedPostings)만 본다.
        boolean passed = unbalancedPostings == 0;

        return new LedgerResult(unbalancedPostings, cardReceivableBalance, passed);
    }

    public record PgInternalResult(
            long doneWithoutApprove,
            long approveWithoutDone,
            long captureWithoutApprove,
            long approvedWithoutCapture,
            long unknownRemaining,
            long pendingOutbox,
            long reconDiscrepancy,
            long processingIdempotencyKeys,
            boolean passed
    ) {}

    public record LedgerResult(
            long unbalancedPostings,
            long cardNetworkReceivableBalance,
            boolean passed
    ) {}
}
