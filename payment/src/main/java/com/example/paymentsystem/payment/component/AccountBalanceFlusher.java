package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AccountBalanceFlusher {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    @Scheduled(fixedDelay = 10_000)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void flush() {
        List<Long> accountIds = ledgerRepository.findAccountIdsWithUnapplied();
        for (Long accountId : accountIds) {
            Account account = accountRepository.findByIdForUpdate(accountId).orElseThrow();
            long debit = ledgerRepository.sumUnappliedByDirection(accountId, LedgerDirection.DEBIT);
            long credit = ledgerRepository.sumUnappliedByDirection(accountId, LedgerDirection.CREDIT);
            long delta = account.computeNetDelta(debit, credit);
            if (delta != 0) {
                account.applyDelta(delta);
            }
            ledgerRepository.markApplied(accountId);
        }
    }
}
