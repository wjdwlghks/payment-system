package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.domain.RefundRiskFlag;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.RefundRiskFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundRiskService {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final RefundRiskFlagRepository riskFlagRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkAndRecord(String merchantId, Long refundTxId, Long refundAmount) {
        long pendingBalance = balanceNow(AccountType.MERCHANT_PENDING, merchantId);
        long availableBalance = balanceNow(AccountType.MERCHANT_AVAILABLE, merchantId);
        long netPosition = pendingBalance + availableBalance;

        if (netPosition < 0) {
            riskFlagRepository.save(new RefundRiskFlag(
                    merchantId, refundTxId, pendingBalance, availableBalance, refundAmount, netPosition
            ));
        }
    }

    private long balanceNow(AccountType type, String merchantId) {
        return accountRepository.findByAccountTypeAndMerchantId(type, merchantId)
                .map(a -> {
                    long d = ledgerRepository.sumUnappliedByDirection(a.getId(), LedgerDirection.DEBIT);
                    long c = ledgerRepository.sumUnappliedByDirection(a.getId(), LedgerDirection.CREDIT);
                    return a.getBalance() + a.computeNetDelta(d, c);
                })
                .orElse(0L);
    }
}
