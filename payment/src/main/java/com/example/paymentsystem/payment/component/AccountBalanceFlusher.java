package com.example.paymentsystem.payment.component;

import com.example.paymentsystem.payment.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AccountBalanceFlusher {

    private final LedgerRepository ledgerRepository;
    private final AccountFlushExecutor accountFlushExecutor;

    @Scheduled(fixedDelay = 10_000)
    public void flush() {
        List<Long> accountIds = ledgerRepository.findAccountIdsWithUnapplied();
        for (Long accountId : accountIds) {
            accountFlushExecutor.flushAccount(accountId);
        }
    }
}
