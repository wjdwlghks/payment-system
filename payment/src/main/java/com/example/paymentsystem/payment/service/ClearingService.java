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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
    public ClearingBatchResponse clearing() {
        Instant windowEnd = Instant.now().minus(10, ChronoUnit.SECONDS);
        Optional<Instant> optionalWindowStart = windowStart();

        if (optionalWindowStart.isEmpty()) {
            return ClearingBatchResponse.noTarget(null, windowEnd);
        }

        Instant windowStart = optionalWindowStart.get();

        List<PaymentTransaction> transactions = paymentTransactionRepository.findUnclearedCaptureTransactions(
                TransactionStatus.SUCCEEDED,
                TransactionType.CAPTURE,
                windowStart,
                windowEnd
        );

        if (transactions.isEmpty()) {
            return ClearingBatchResponse.noTarget(windowStart, windowEnd);
        }

        Long totalAmount = 0L;
        int itemCount = 0;
        for (PaymentTransaction transaction : transactions) {
            totalAmount += transaction.getAmount();
            itemCount ++;
        }

        ClearingBatch batch = clearingBatchRepository.save(
                new ClearingBatch(
                        generateBatchCode(),
                        windowStart,
                        windowEnd,
                        totalAmount,
                        itemCount
                )
        );

        for (PaymentTransaction transaction : transactions) {
            clearingBatchItemRepository.save(
                    new ClearingBatchItem(
                            batch,
                            transaction,
                            transaction.getAmount()
                    )
            );
        }

        ledgerService.postClearing(batch.getId(), totalAmount);

        batch.markCleared();

        return ClearingBatchResponse.created(batch);
    }

    private Optional<Instant> windowStart() {
        return clearingBatchRepository
                .findTop1ByStatusOrderByWindowEndDesc(ClearingBatchStatus.CLEARED)
                .map(ClearingBatch::getWindowEnd)
                .or(this::oldestUnclearedCaptureUpdatedAt);
    }

    private Optional<Instant> oldestUnclearedCaptureUpdatedAt() {
        return paymentTransactionRepository.findOldestUnclearedTransactions(
                        TransactionStatus.SUCCEEDED,
                        TransactionType.CAPTURE,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .map(PaymentTransaction::getUpdatedAt);
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
