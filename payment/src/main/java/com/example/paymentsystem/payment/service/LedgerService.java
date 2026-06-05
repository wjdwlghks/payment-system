package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerPostingRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final String GLOBAL = "GLOBAL";

    private final LedgerRepository ledgerRepository;
    private final LedgerPostingRepository ledgerPostingRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void postCapture(Long captureTransactionId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = paymentTransactionRepository.findById(captureTransactionId)
                .orElseThrow();
        String merchantId = transaction.getPaymentIntent().getMerchantId();

        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);
        Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId);
        Account feeRevenue = globalAccount(AccountType.FEE_REVENUE);

        long amount = transaction.getAmount();
        long fee = calculateFee(amount);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(cardReceivable, LedgerDirection.DEBIT, amount, LedgerEntryType.CAPTURE));
        entries.add(new LedgerEntry(merchantPending, LedgerDirection.CREDIT, amount - fee, LedgerEntryType.CAPTURE));
        if (fee > 0) {
            entries.add(new LedgerEntry(feeRevenue, LedgerDirection.CREDIT, fee, LedgerEntryType.CAPTURE));
        }

        post(LedgerPostingType.CAPTURE, sourceType, captureTransactionId.toString(), entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postRefund(Long refundTransactionId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = paymentTransactionRepository.findById(refundTransactionId)
                .orElseThrow();
        String merchantId = transaction.getPaymentIntent().getMerchantId();

        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);
        Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId);
        Account merchantAvailable = merchantAccount(AccountType.MERCHANT_AVAILABLE, merchantId);

        long refundAmount = transaction.getAmount();
        long takeFromPending = Math.clamp(merchantPending.getBalance(), 0L, refundAmount);
        long takeFromAvailable = refundAmount - takeFromPending;

        List<LedgerEntry> entries = new ArrayList<>();
        if (takeFromPending > 0) {
            entries.add(new LedgerEntry(merchantPending, LedgerDirection.DEBIT, takeFromPending, LedgerEntryType.REFUND));
        }
        if (takeFromAvailable > 0) {
            entries.add(new LedgerEntry(merchantAvailable, LedgerDirection.DEBIT, takeFromAvailable, LedgerEntryType.REFUND));
        }
        entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, refundAmount, LedgerEntryType.REFUND));

        post(LedgerPostingType.REFUND, sourceType, refundTransactionId.toString(), entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postClearing(Long batchId, Long totalAmount) {
        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);
        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, totalAmount, LedgerEntryType.CLEARING));
        entries.add(new LedgerEntry(bank, LedgerDirection.DEBIT, totalAmount, LedgerEntryType.CLEARING));

        post(LedgerPostingType.CLEARING, LedgerSourceType.CLEARING_BATCH, batchId.toString(), entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postSettlement(Long runId, List<PaymentTransaction> transactions) {
        List<LedgerEntry> entries = new ArrayList<>();

        for (PaymentTransaction transaction : transactions) {
            String merchantId = transaction.getPaymentIntent().getMerchantId();
            Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId);
            Account merchantAvailable = merchantAccount(AccountType.MERCHANT_AVAILABLE, merchantId);

            long net = transaction.getAmount() - calculateFee(transaction.getAmount());

            entries.add(new LedgerEntry(merchantPending, LedgerDirection.DEBIT, net, LedgerEntryType.SETTLEMENT));
            entries.add(new LedgerEntry(merchantAvailable, LedgerDirection.CREDIT, net, LedgerEntryType.SETTLEMENT));
        }

        post(LedgerPostingType.SETTLEMENT, LedgerSourceType.SETTLEMENT_RUN, runId.toString(), entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postPayout(Long payoutId, String merchantId, Long amount) {
        Account merchantAvailable = merchantAccount(AccountType.MERCHANT_AVAILABLE, merchantId);
        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(merchantAvailable, LedgerDirection.DEBIT, amount, LedgerEntryType.PAYOUT));
        entries.add(new LedgerEntry(bank, LedgerDirection.CREDIT, amount, LedgerEntryType.PAYOUT));

        post(LedgerPostingType.PAYOUT, LedgerSourceType.PAYOUT_REQUEST, payoutId.toString(), entries);
    }

    private void post(
            LedgerPostingType postingType,
            LedgerSourceType sourceType,
            String sourceId,
            List<LedgerEntry> entries
    ) {
        LedgerPosting posting = ledgerPostingRepository.save(
                new LedgerPosting(
                        postingType,
                        sourceType,
                        sourceId,
                        total(entries, LedgerDirection.DEBIT),
                        total(entries, LedgerDirection.CREDIT)
                )
        );

        for (LedgerEntry entry : entries) {
            entry.assignPosting(posting);
            entry.getAccount().apply(entry.getDirection(), entry.getAmount());
        }

        ledgerRepository.saveAll(entries);
    }

    private Account globalAccount(AccountType type) {
        return accountRepository.findByAccountTypeAndMerchantIdForUpdate(type, GLOBAL).orElseThrow();
    }

    private Account merchantAccount(AccountType type, String merchantId) {
        return accountRepository.findByAccountTypeAndMerchantIdForUpdate(type, merchantId)
                .orElseGet(() -> accountRepository.save(new Account(type, AccountClass.LIABILITY, merchantId)));
    }

    private long total(List<LedgerEntry> entries, LedgerDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .mapToLong(LedgerEntry::getAmount)
                .sum();
    }

    private long calculateFee(long amount) {
        long numerator = Math.multiplyExact(amount, 3L);
        return Math.addExact(numerator, 50L) / 100L;
    }
}
