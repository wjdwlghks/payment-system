package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AccountFlushExecutor {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    // 계정별 REQUIRES_NEW — 계정마다 X-lock을 즉시 커밋해서 풀어준다.
    // 한 트랜잭션에 여러 계정을 몰아 처리하면 먼저 잠근 계정의 락을 마지막 계정까지 들고 있게 되어
    // 그 사이 핫패스(ledger_entry INSERT의 FK 체크 S-lock)가 불필요하게 오래 대기하게 된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void flushAccount(Long accountId) {
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
