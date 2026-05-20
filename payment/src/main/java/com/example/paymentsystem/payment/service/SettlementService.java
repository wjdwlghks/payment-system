package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.SettlementRunResponse;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.example.paymentsystem.payment.repository.SettlementItemRepository;
import com.example.paymentsystem.payment.repository.SettlementRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final ZoneId RUN_CODE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RUN_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final LedgerService ledgerService;
    private final SettlementRunRepository settlementRunRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    @Transactional
    public SettlementRunResponse settlementRun() {
        Instant windowEnd = Instant.now().minus(10, ChronoUnit.SECONDS);
        Optional<Instant> optionalWindowStart = windowStart();

        if (optionalWindowStart.isEmpty()) {
            return SettlementRunResponse.noTarget(null, windowEnd);
        }

        Instant windowStart = optionalWindowStart.get();

        List<PaymentTransaction> transactions = paymentTransactionRepository.findClearedTransactions(
                TransactionStatus.SUCCEEDED,
                TransactionType.CAPTURE,
                ClearingBatchStatus.CLEARED,
                windowStart,
                windowEnd
        );

        if (transactions.isEmpty()) {
            return SettlementRunResponse.noTarget(windowStart, windowEnd);
        }

        long totalAmount = 0L;
        int itemCount = 0;
        for (PaymentTransaction transaction : transactions) {
            totalAmount += transaction.getAmount() - calculateFee(transaction.getAmount());
            itemCount ++;
        }

        SettlementRun settlementRun = settlementRunRepository.save(
                new SettlementRun(
                        generateRunCode(),
                        windowStart,
                        windowEnd,
                        totalAmount,
                        itemCount
                )
        );

        for (PaymentTransaction transaction : transactions) {
            settlementItemRepository.save(
                    new SettlementRunItem(
                            settlementRun,
                            transaction,
                            transaction.getPaymentIntent().getMerchantId(),
                            transaction.getAmount() - calculateFee(transaction.getAmount())
                    )
            );
        }

        ledgerService.postSettlement(settlementRun.getId(), transactions);

        settlementRun.markSettled();

        return SettlementRunResponse.created(settlementRun);
    }

    private Optional<Instant> windowStart() {
        return settlementRunRepository
                .findTop1ByStatusOrderByWindowEndDesc(SettlementRunStatus.SETTLED)
                .map(SettlementRun::getWindowEnd)
                .or(this::oldestClearedCaptureUpdatedAt);
    }

    private Optional<Instant> oldestClearedCaptureUpdatedAt() {
        return paymentTransactionRepository.findOldestClearedTransactions(
                TransactionStatus.SUCCEEDED,
                TransactionType.CAPTURE,
                ClearingBatchStatus.CLEARED,
                PageRequest.of(0, 1)
        )
                .stream()
                .findFirst()
                .map(PaymentTransaction::getUpdatedAt);
    }

    private String generateRunCode() {
        String prefix = "STL-" + LocalDate.now(RUN_CODE_ZONE).format(RUN_CODE_DATE_FORMATTER) + "-";
        int nextSequence = settlementRunRepository
                .findTop1ByRunCodeStartingWithOrderByRunCodeDesc(prefix)
                .map(SettlementRun::getRunCode)
                .map(runCode -> runCode.substring(prefix.length()))
                .map(Integer::parseInt)
                .orElse(0) + 1;

        return prefix + String.format("%03d", nextSequence);
    }

    private long calculateFee(long amount) {
        long numerator = Math.multiplyExact(amount, 3L);
        return Math.addExact(numerator, 50L) / 100L;
    }
}
