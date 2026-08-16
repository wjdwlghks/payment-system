package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import com.example.paymentsystem.payment.domain.Payout;
import com.example.paymentsystem.payment.dto.PayoutResponse;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PayoutService {

    private static final ZoneId PAYOUT_CODE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter PAYOUT_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final PayoutRepository payoutRepository;

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PayoutResponse payout(String merchantId) {
        Optional<Account> optionalMerchantPending = accountRepository.findByAccountTypeAndMerchantId(
                AccountType.MERCHANT_PENDING,
                merchantId
        );

        if (optionalMerchantPending.isEmpty()) {
            return PayoutResponse.noTarget(merchantId);
        }

        Account merchantPending = optionalMerchantPending.get();
        Long amount = merchantPending.getBalance();

        if (amount <= 0) {
            return PayoutResponse.noTarget(merchantId);
        }

        Payout payout = payoutRepository.save(
                new Payout(
                        generatePayoutCode(),
                        merchantId,
                        amount
                )
        );

        ledgerService.postPayout(payout.getId(), merchantId, amount);

        payout.markPaid();

        return PayoutResponse.created(payout);
    }

    /**
     * 코드는 {@code PAY-yyyyMMdd-001} 꼴이다. 패딩은 세 자리지만 1,000번째부터는 네 자리가 되고,
     * 그래도 되는 이유는 다음 번호를 {@link PayoutRepository#findMaxSequenceByPrefix}가
     * <b>수로</b> 뽑기 때문이다. 문자열 정렬로 뽑으면 그 자리에서 번호가 멈춘다.
     */
    private String generatePayoutCode() {
        String prefix = "PAY-" + LocalDate.now(PAYOUT_CODE_ZONE).format(PAYOUT_CODE_DATE_FORMATTER) + "-";
        long nextSequence = payoutRepository.findMaxSequenceByPrefix(prefix) + 1;

        return prefix + String.format("%03d", nextSequence);
    }
}
