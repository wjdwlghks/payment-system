package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerPostingRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;

import java.time.Instant;
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
    public void postCapture(Long captureTransactionId) {
        PaymentTransaction transaction = paymentTransactionRepository.findById(captureTransactionId)
                .orElseThrow();
        PaymentIntent paymentIntent = transaction.getPaymentIntent();
        String merchantId = paymentIntent.getMerchantId();

        Account cardReceivable = accountRepository.findByAccountTypeAndMerchantId(AccountType.CARD_NETWORK_RECEIVABLE, GLOBAL)
                .orElseThrow();
        Account merchantPending = accountRepository.findByAccountTypeAndMerchantId(AccountType.MERCHANT_PENDING, merchantId)
                .orElseGet(() -> accountRepository.save(
                        new Account(AccountType.MERCHANT_PENDING, AccountClass.LIABILITY, merchantId))
                );
        Account feeRevenue = accountRepository.findByAccountTypeAndMerchantId(AccountType.FEE_REVENUE, GLOBAL)
                .orElseThrow();


        Long fee = calculateFee(transaction.getAmount());

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(
                cardReceivable,
                LedgerDirection.DEBIT,
                transaction.getAmount(),
                LedgerEntryType.CAPTURE
        ));
        entries.add(new LedgerEntry(
                merchantPending,
                LedgerDirection.CREDIT,
                transaction.getAmount() - fee,
                LedgerEntryType.CAPTURE
        ));
        if (fee > 0) {
            entries.add(new LedgerEntry(
                    feeRevenue,
                    LedgerDirection.CREDIT,
                    fee,
                    LedgerEntryType.CAPTURE
            ));
        }

        LedgerPosting posting = ledgerPostingRepository.save(
                new LedgerPosting(
                        LedgerPostingType.CAPTURE,
                        LedgerSourceType.PAYMENT_TRANSACTION,
                        captureTransactionId.toString(),
                        total(entries, LedgerDirection.DEBIT),
                        total(entries, LedgerDirection.CREDIT)
                )
        );

        entries.forEach(entry -> {
            entry.assignPosting(posting);
            entry.getAccount().apply(entry.getDirection(), entry.getAmount());
        });

        ledgerRepository.saveAll(entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postClearing(Long batchId, Long totalAmount) {
        Account cardReceivable = accountRepository.findByAccountTypeAndMerchantId(AccountType.CARD_NETWORK_RECEIVABLE, GLOBAL)
                .orElseThrow();
        Account bank = accountRepository.findByAccountTypeAndMerchantId(AccountType.BANK_ACCOUNT, GLOBAL)
                .orElseThrow();


        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(
                cardReceivable,
                LedgerDirection.CREDIT,
                totalAmount,
                LedgerEntryType.CLEARING
        ));
        entries.add(new LedgerEntry(
                bank,
                LedgerDirection.DEBIT,
                totalAmount,
                LedgerEntryType.CLEARING
        ));

        LedgerPosting posting = ledgerPostingRepository.save(
                new LedgerPosting(
                        LedgerPostingType.CLEARING,
                        LedgerSourceType.CLEARING_BATCH,
                        batchId.toString(),
                        total(entries, LedgerDirection.DEBIT),
                        total(entries, LedgerDirection.CREDIT)
                )
        );

        entries.forEach(entry -> {
            entry.assignPosting(posting);
            entry.getAccount().apply(entry.getDirection(), entry.getAmount());
        });

        ledgerRepository.saveAll(entries);
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
