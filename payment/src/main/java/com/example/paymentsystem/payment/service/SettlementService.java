package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.SettlementRunResponse;
import com.example.paymentsystem.payment.repository.ClearingBatchRepository;
import com.example.paymentsystem.payment.repository.SettlementItemRepository;
import com.example.paymentsystem.payment.repository.SettlementRunRepository;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final ZoneId RUN_CODE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RUN_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final LedgerService ledgerService;
    private final SettlementRunRepository settlementRunRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final ClearingBatchRepository clearingBatchRepository;

    // 카드사 -> PG 정산(SETTLEMENT): CLEARED된 배치 중 아직 정산 안 된 것들을 모아 은행 입금을 반영한다.
    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public SettlementRunResponse settlementRun() {
        // ClearingBatch는 CLEARED로 전이된 뒤 다시 갱신되지 않는 불변 레코드라,
        // PaymentTransaction 스캔 때 쓰던 10초 디바운스 버퍼가 필요 없다(clearing 직후 바로 정산해도 안전).
        Instant windowEnd = Instant.now();
        Optional<Instant> optionalWindowStart = windowStart();

        if (optionalWindowStart.isEmpty()) {
            return SettlementRunResponse.noTarget(null, windowEnd);
        }

        Instant windowStart = optionalWindowStart.get();

        List<ClearingBatch> batches = clearingBatchRepository.findUnsettledClearedBatches(
                ClearingBatchStatus.CLEARED,
                windowStart,
                windowEnd
        );

        if (batches.isEmpty()) {
            return SettlementRunResponse.noTarget(windowStart, windowEnd);
        }

        long totalAmount = 0L;
        int itemCount = 0;
        for (ClearingBatch batch : batches) {
            totalAmount += batch.getTotalAmount() - calculateCardNetworkFee(batch.getTotalAmount());
            itemCount++;
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

        for (ClearingBatch batch : batches) {
            long net = batch.getTotalAmount() - calculateCardNetworkFee(batch.getTotalAmount());
            settlementItemRepository.save(
                    new SettlementRunItem(settlementRun, batch, net)
            );
        }

        ledgerService.postSettlement(settlementRun.getId(), batches);

        settlementRun.markSettled();

        return SettlementRunResponse.created(settlementRun);
    }

    private Optional<Instant> windowStart() {
        return settlementRunRepository
                .findTop1ByStatusOrderByWindowEndDesc(SettlementRunStatus.SETTLED)
                .map(SettlementRun::getWindowEnd)
                .or(this::oldestUnsettledClearedBatchAt);
    }

    private Optional<Instant> oldestUnsettledClearedBatchAt() {
        return clearingBatchRepository.findOldestUnsettledClearedBatches(
                        ClearingBatchStatus.CLEARED,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .map(ClearingBatch::getClearedAt);
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

    private long calculateCardNetworkFee(long amount) {
        long numerator = Math.multiplyExact(amount, 1L);
        return Math.addExact(numerator, 50L) / 100L;
    }
}
