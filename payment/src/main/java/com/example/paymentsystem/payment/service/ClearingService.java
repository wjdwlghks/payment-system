package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.repository.ClearingBatchItemRepository;
import com.example.paymentsystem.payment.repository.ClearingBatchRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 청산은 대사(ReconciliationValidationService.validate)를 통해서만 일어난다.
 * 대사 대상 조회가 이미 청산된 tx를 제외하므로(ClearingBatchItem NOT EXISTS),
 * 대사 밖에서 먼저 청산하면 대사가 AGGREGATE 불일치로 깨진다.
 */
@Service
@RequiredArgsConstructor
public class ClearingService {

    private static final ZoneId BATCH_CODE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BATCH_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ClearingBatchRepository clearingBatchRepository;
    private final ClearingBatchItemRepository clearingBatchItemRepository;
    private final LedgerService ledgerService;

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public ClearingBatch clearForReconciliation(
            ReconBatch reconBatch,
            long netAmount,
            List<PaymentTransaction> reconciledTransactions
    ) {
        if (netAmount <= 0) {
            throw new IllegalArgumentException("netAmount must be positive, was: " + netAmount);
        }
        Instant windowStart = reconBatch.getBusinessDate().atStartOfDay(BATCH_CODE_ZONE).toInstant();
        Instant windowEnd = reconBatch.getBusinessDate().plusDays(1).atStartOfDay(BATCH_CODE_ZONE).toInstant();

        ClearingBatch batch = clearingBatchRepository.save(
                new ClearingBatch(
                        generateBatchCode(),
                        windowStart,
                        windowEnd,
                        netAmount,
                        reconciledTransactions.size()
                )
        );

        for (PaymentTransaction tx : reconciledTransactions) {
            clearingBatchItemRepository.save(
                    new ClearingBatchItem(batch, tx, tx.getAmount())
            );
        }

        ledgerService.postClearing(batch.getId(), netAmount);

        batch.markCleared();

        return batch;
    }

    private String generateBatchCode() {
        String prefix = "CLR-" + LocalDate.now(BATCH_CODE_ZONE).format(BATCH_CODE_DATE_FORMATTER) + "-";
        int nextSequence = clearingBatchRepository
                .findTop1ByBatchCodeStartingWithOrderByBatchCodeDesc(prefix)
                .map(ClearingBatch::getBatchCode)
                .map(batchCode -> batchCode.substring(prefix.length()))
                .map(Integer::parseInt)
                .orElse(0) + 1;

        return prefix + String.format("%03d", nextSequence);
    }
}
