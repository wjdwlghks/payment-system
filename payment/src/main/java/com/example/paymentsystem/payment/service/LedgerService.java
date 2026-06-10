package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerPostingRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final String GLOBAL = "GLOBAL";
    private static final int SHARD_COUNT = 16;

    private final LedgerRepository ledgerRepository;
    private final LedgerPostingRepository ledgerPostingRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void postCapture(Long captureTransactionId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = paymentTransactionRepository.findById(captureTransactionId)
                .orElseThrow();
        String merchantId = transaction.getPaymentIntent().getMerchantId();
        String paymentKey = transaction.getPaymentIntent().getPaymentKey();

        int bucket = bucketIndex(paymentKey);
        Account cardReceivable = cardReceivableBucketForUpdate(bucket);
        Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId);
        Account feeRevenue = shardedGlobalAccount(AccountType.FEE_REVENUE, bucket);

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
        String paymentKey = transaction.getPaymentIntent().getPaymentKey();

        Account cardReceivable = cardReceivableBucketForUpdate(bucketIndex(paymentKey));
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
    public void postClearing(Long batchId, List<PaymentTransaction> transactions) {
        // paymentKey 해시 기준으로 bucket별 net 계산
        // CAPTURE → +amount, REFUND → -amount
        TreeMap<Integer, Long> bucketNet = new TreeMap<>();
        for (PaymentTransaction tx : transactions) {
            int bucket = bucketIndex(tx.getPaymentIntent().getPaymentKey());
            long delta = tx.getType() == TransactionType.CAPTURE ? tx.getAmount() : -tx.getAmount();
            bucketNet.merge(bucket, delta, Long::sum);
        }

        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        // 오름차순(TreeMap 기본)으로 순회하여 lock 순서 고정
        for (var entry : bucketNet.entrySet()) {
            int bucket = entry.getKey();
            long net = entry.getValue();
            if (net == 0) continue;

            Account cardReceivable = cardReceivableBucketForUpdate(bucket);
            List<LedgerEntry> entries = new ArrayList<>();

            if (net > 0) {
                entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, net, LedgerEntryType.CLEARING));
                entries.add(new LedgerEntry(bank, LedgerDirection.DEBIT, net, LedgerEntryType.CLEARING));
            } else {
                entries.add(new LedgerEntry(cardReceivable, LedgerDirection.DEBIT, -net, LedgerEntryType.CLEARING));
                entries.add(new LedgerEntry(bank, LedgerDirection.CREDIT, -net, LedgerEntryType.CLEARING));
            }

            post(LedgerPostingType.CLEARING, LedgerSourceType.CLEARING_BATCH,
                    batchId + "-" + bucket, entries);
        }
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

    private Account cardReceivableBucketForUpdate(int bucket) {
        return accountRepository.findByAccountTypeAndMerchantIdAndBucketIndexForUpdate(
                AccountType.CARD_NETWORK_RECEIVABLE, GLOBAL, bucket).orElseThrow();
    }

    private Account shardedGlobalAccount(AccountType type, int bucket) {
        return accountRepository.findByAccountTypeAndMerchantIdAndBucketIndexForUpdate(type, GLOBAL, bucket).orElseThrow();
    }

    private Account globalAccount(AccountType type) {
        return accountRepository.findByAccountTypeAndMerchantIdAndBucketIndexForUpdate(type, GLOBAL, 0).orElseThrow();
    }

    private Account merchantAccount(AccountType type, String merchantId) {
        return accountRepository.findByAccountTypeAndMerchantIdAndBucketIndexForUpdate(type, merchantId, 0)
                .orElseGet(() -> accountRepository.save(new Account(type, AccountClass.LIABILITY, merchantId)));
    }

    private int bucketIndex(String paymentKey) {
        return Math.abs(paymentKey.hashCode()) % SHARD_COUNT;
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
