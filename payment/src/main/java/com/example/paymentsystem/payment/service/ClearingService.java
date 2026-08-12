package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.ClearingBatchResponse;
import com.example.paymentsystem.payment.repository.ClearingBatchItemRepository;
import com.example.paymentsystem.payment.repository.ClearingBatchRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

@Service
@RequiredArgsConstructor
public class ClearingService {

    private static final ZoneId BATCH_CODE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BATCH_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int CLEARING_LIMIT = 5000;

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ClearingBatchRepository clearingBatchRepository;
    private final ClearingBatchItemRepository clearingBatchItemRepository;
    private final LedgerService ledgerService;

    // 매입이 배치가 되면서 시간 window가 필요 없어졌다 — 미청산 매입을 전부 긁는다.
    // UNKNOWN 매입이 뒤늦게 확정돼도 다음 청산에서 집히고, ClearingBatchItem.tx_id UNIQUE가 이중청산을 막는다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public ClearingBatchResponse clearing() {
        List<PaymentTransaction> transactions = paymentTransactionRepository.findOldestUnclearedTransactions(
                TransactionStatus.SUCCEEDED,
                TransactionType.CAPTURE,
                PageRequest.of(0, CLEARING_LIMIT)
        );

        if (transactions.isEmpty()) {
            return ClearingBatchResponse.noTarget(null, Instant.now());
        }

        Instant windowStart = transactions.get(0).getUpdatedAt();
        Instant windowEnd = transactions.get(transactions.size() - 1).getUpdatedAt().plusMillis(1);

        long totalAmount = 0L;
        for (PaymentTransaction transaction : transactions) {
            totalAmount += transaction.getAmount();
        }

        ClearingBatch batch = clearingBatchRepository.save(
                new ClearingBatch(generateBatchCode(), windowStart, windowEnd, totalAmount, transactions.size())
        );

        for (PaymentTransaction transaction : transactions) {
            clearingBatchItemRepository.save(new ClearingBatchItem(batch, transaction, transaction.getAmount()));
        }

        ledgerService.postClearing(batch.getId(), totalAmount);
        batch.markCleared();

        return ClearingBatchResponse.created(batch);
    }

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
