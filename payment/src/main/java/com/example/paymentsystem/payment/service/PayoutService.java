package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import com.example.paymentsystem.payment.domain.Payout;
import com.example.paymentsystem.payment.dto.PayoutResponse;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public PayoutResponse payout(String merchantId) {
        Optional<Account> optionalMerchantAvailable = accountRepository.findByAccountTypeAndMerchantId(
                AccountType.MERCHANT_AVAILABLE,
                merchantId
        );

        if (optionalMerchantAvailable.isEmpty()) {
            return PayoutResponse.noTarget(merchantId);
        }

        Account merchantAvailable = optionalMerchantAvailable.get();
        Long amount = merchantAvailable.getBalance();

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

    private String generatePayoutCode() {
        String prefix = "PAY-" + LocalDate.now(PAYOUT_CODE_ZONE).format(PAYOUT_CODE_DATE_FORMATTER) + "-";
        int nextSequence = payoutRepository
                .findTop1ByPayoutCodeStartingWithOrderByPayoutCodeDesc(prefix)
                .map(Payout::getPayoutCode)
                .map(payoutCode -> payoutCode.substring(prefix.length()))
                .map(Integer::parseInt)
                .orElse(0) + 1;

        return prefix + String.format("%03d", nextSequence);
    }
}
