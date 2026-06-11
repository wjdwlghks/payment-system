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
        long doneWithoutCapture        = txRepository.countDoneWithoutCaptureSucceeded();
        long captureWithoutDone        = txRepository.countCaptureSucceededWithoutDone();
        long unknownRemaining          = txRepository.countByStatus(TransactionStatus.UNKNOWN);
        long pendingOutbox             = outboxRepository.countByStatus(WebhookOutboxStatus.PENDING);
        long reconDiscrepancy          = reconBatchRepository.sumDiscrepancyCount();
        long processingIdempotencyKeys = idempotencyKeyRepository.countByStatus(IdempotentStatus.PROCESSING);

        boolean passed = doneWithoutCapture == 0 && captureWithoutDone == 0
                && unknownRemaining == 0
                && reconDiscrepancy == 0 && processingIdempotencyKeys == 0;

        return new PgInternalResult(
                doneWithoutCapture, captureWithoutDone,
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

        boolean passed = unbalancedPostings == 0 && cardReceivableBalance == 0;

        return new LedgerResult(unbalancedPostings, cardReceivableBalance, passed);
    }

    public record PgInternalResult(
            long doneWithoutCapture,
            long captureWithoutDone,
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
