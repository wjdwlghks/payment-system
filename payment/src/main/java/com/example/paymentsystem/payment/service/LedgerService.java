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

    // CLEARING: 카드사와 대사(reconciliation)가 끝난 net 금액에서 카드사 매입 수수료만 확정 반영.
    // 은행 계좌 입금은 SETTLEMENT의 몫이라 여기서는 CARD_NETWORK_RECEIVABLE을 건드리지 않는다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postClearing(Long batchId, Long fileNet) {
        Account cardNetworkFee = globalAccount(AccountType.CARD_NETWORK_FEE);
        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);

        long fee = calculateCardNetworkFee(fileNet);

        List<LedgerEntry> entries = new ArrayList<>();
        if (fee > 0) {
            entries.add(new LedgerEntry(cardNetworkFee, LedgerDirection.DEBIT, fee, LedgerEntryType.CLEARING));
            entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, fee, LedgerEntryType.CLEARING));
        }

        post(LedgerPostingType.CLEARING, LedgerSourceType.CLEARING_BATCH, batchId.toString(), entries);
    }

    // SETTLEMENT: 카드사 → PG 실제 입금. CLEARED된 배치별로 (총액 - 카드사수수료) 순액이 은행 계좌에 반영된다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postSettlement(Long runId, List<ClearingBatch> batches) {
        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);
        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        List<LedgerEntry> entries = new ArrayList<>();
        for (ClearingBatch batch : batches) {
            long fee = calculateCardNetworkFee(batch.getTotalAmount());
            long net = batch.getTotalAmount() - fee;

            entries.add(new LedgerEntry(bank, LedgerDirection.DEBIT, net, LedgerEntryType.SETTLEMENT));
            entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, net, LedgerEntryType.SETTLEMENT));
        }

        post(LedgerPostingType.SETTLEMENT, LedgerSourceType.SETTLEMENT_RUN, runId.toString(), entries);
    }

    // PAYOUT: PG → 가맹점 실제 송금. 가맹점 계좌는 PENDING/AVAILABLE 구분 없이 단일 계좌.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postPayout(Long payoutId, String merchantId, Long amount) {
        Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId);
        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(merchantPending, LedgerDirection.DEBIT, amount, LedgerEntryType.PAYOUT));
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
            // account.apply() 호출 없음 — AccountBalanceFlusher가 주기적으로 갱신
        }

        ledgerRepository.saveAll(entries);
    }

    private Account globalAccount(AccountType type) {
        return accountRepository.findByAccountTypeAndMerchantId(type, GLOBAL).orElseThrow();
    }

    private Account merchantAccount(AccountType type, String merchantId) {
        return accountRepository.findByAccountTypeAndMerchantId(type, merchantId)
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

    // 카드사가 정산 시 떼어가는 매입 수수료율(임의값 1%) — PG-가맹점 수수료(calculateFee)와는 별개 축.
    private long calculateCardNetworkFee(long amount) {
        long numerator = Math.multiplyExact(amount, 1L);
        return Math.addExact(numerator, 50L) / 100L;
    }
}
