package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import com.example.paymentsystem.payment.domain.CardCompany;
import com.example.paymentsystem.payment.domain.ClearingBatch;
import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.domain.LedgerEntryType;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.ReconBatch;
import com.example.paymentsystem.payment.domain.ReconBatchAbortReason;
import com.example.paymentsystem.payment.domain.ReconBatchStatus;
import com.example.paymentsystem.payment.domain.ReconciliationResult;
import com.example.paymentsystem.payment.domain.SettlementStatus;
import com.example.paymentsystem.payment.domain.SettlementType;
import com.example.paymentsystem.payment.domain.StagingSettlement;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.ValidateReconciliationResponse;
import com.example.paymentsystem.payment.exception.ReconciliationCarryOverException;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import com.example.paymentsystem.payment.repository.ReconBatchRepository;
import com.example.paymentsystem.payment.repository.ReconciliationResultRepository;
import com.example.paymentsystem.payment.repository.StagingSettlementRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationValidationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String GLOBAL = "GLOBAL";
    private static final List<TransactionType> RECON_TYPES =
            List.of(TransactionType.CAPTURE, TransactionType.REFUND);
    private static final List<TransactionStatus> RECON_STATUSES =
            List.of(TransactionStatus.SUCCEEDED, TransactionStatus.FAIL);
    private static final List<TransactionStatus> UNKNOWN_STATUS =
            List.of(TransactionStatus.UNKNOWN);
    private static final List<LedgerEntryType> RECON_LEDGER_TYPES =
            List.of(LedgerEntryType.CAPTURE, LedgerEntryType.REFUND, LedgerEntryType.CLEARING);

    private final ReconBatchRepository reconBatchRepository;
    private final StagingSettlementRepository stagingSettlementRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    private final ClearingService clearingService;
    private final ChunkProcessor chunkProcessor;
    private final UnknownReconciler unknownReconciler;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ValidateReconciliationResponse validate(Long batchId) {
        ReconBatch batch = reconBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("recon batch not found: " + batchId));
        if (batch.getStatus() != ReconBatchStatus.INGESTED) {
            throw new IllegalStateException(
                    "batch must be INGESTED to validate, was: " + batch.getStatus()
            );
        }

        CardCompany cardCompany = CardCompany.valueOf(batch.getCardCompany());

        Instant rangeStart = batch.getBusinessDate().atStartOfDay(KST).toInstant();
        Instant rangeEnd = batch.getBusinessDate().plusDays(1).atStartOfDay(KST).toInstant();

        Account cardReceivable = accountRepository
                .findByAccountTypeAndMerchantId(AccountType.CARD_NETWORK_RECEIVABLE, GLOBAL)
                .orElseThrow(() -> new IllegalStateException("CARD_NETWORK_RECEIVABLE account not found"));

        long debit = ledgerRepository.sumUnappliedByDirection(cardReceivable.getId(), LedgerDirection.DEBIT);
        long credit = ledgerRepository.sumUnappliedByDirection(cardReceivable.getId(), LedgerDirection.CREDIT);
        long currentBalance = cardReceivable.getBalance() + cardReceivable.computeNetDelta(debit, credit);
        long thisBatchLedgerNet = computeLedgerNet(List.of(cardReceivable.getId()), rangeStart, rangeEnd);
        long previousBalance = currentBalance - thisBatchLedgerNet;
        if (previousBalance != 0) {
            ReconciliationCarryOverException carryOver = new ReconciliationCarryOverException(previousBalance);
            chunkProcessor.markAborted(batchId, ReconBatchAbortReason.CARRY_OVER, carryOver.getMessage());
            throw carryOver;
        }

        List<StagingSettlement> stagings = stagingSettlementRepository.findByReconBatch_Id(batchId);

        int autoResolvedCount = reconcileUnknowns(rangeStart, rangeEnd, stagings, cardCompany);

        List<PaymentTransaction> txs = paymentTransactionRepository.findForReconciliation(
                RECON_TYPES, RECON_STATUSES, rangeStart, rangeEnd, cardCompany
        );

        Map<String, StagingSettlement> stagingByCardRequestRef = new HashMap<>();
        for (StagingSettlement s : stagings) {
            stagingByCardRequestRef.put(s.getCardRequestRef(), s);
        }
        Map<String, PaymentTransaction> txByCardRequestRef = new HashMap<>();
        for (PaymentTransaction t : txs) {
            txByCardRequestRef.put(t.getCardRequestRef(), t);
        }

        List<ReconciliationResult> results = new ArrayList<>();
        Counts counts = new Counts();

        for (PaymentTransaction t : txs) {
            if (t.getStatus() != TransactionStatus.SUCCEEDED) {
                continue;
            }
            if (!stagingByCardRequestRef.containsKey(t.getCardRequestRef())) {
                results.add(ReconciliationResult.missingOnCard(batch, t.getId(), t.getAmount()));
                counts.missingOnCard++;
            }
        }

        for (StagingSettlement s : stagings) {
            if (s.getTxStatus() != SettlementStatus.APPROVED) {
                continue;
            }
            if (!txByCardRequestRef.containsKey(s.getCardRequestRef())) {
                results.add(ReconciliationResult.missingOnPg(
                        batch, s.getId(), s.getAmount(), s.getApprovalNo()
                ));
                counts.missingOnPg++;
            }
        }

        for (StagingSettlement s : stagings) {
            PaymentTransaction t = txByCardRequestRef.get(s.getCardRequestRef());
            if (t == null) {
                continue;
            }
            boolean txSucceeded = t.getStatus() == TransactionStatus.SUCCEEDED;
            boolean txFail = t.getStatus() == TransactionStatus.FAIL;
            boolean cardApproved = s.getTxStatus() == SettlementStatus.APPROVED;
            boolean cardDeclined = s.getTxStatus() == SettlementStatus.DECLINED;

            if (txSucceeded && cardApproved) {
                if (!s.getAmount().equals(t.getAmount())) {
                    results.add(ReconciliationResult.amountMismatch(
                            batch, s.getId(), t.getId(), t.getAmount(), s.getAmount()
                    ));
                    counts.amountMismatch++;
                }
            } else if ((txSucceeded && cardDeclined) || (txFail && cardApproved)) {
                results.add(ReconciliationResult.statusMismatch(
                        batch, s.getId(), t.getId(),
                        t.getStatus().name(), s.getTxStatus().name()
                ));
                counts.statusMismatch++;
            }
        }

        long fileNet = computeFileNet(stagings);
        long cardCompanyNet = txs.stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCEEDED)
                .mapToLong(t -> t.getType() == TransactionType.CAPTURE ? t.getAmount() : -t.getAmount())
                .sum();
        if (cardCompanyNet != fileNet) {
            results.add(ReconciliationResult.aggregate(batch, cardCompanyNet, fileNet));
            counts.aggregate++;
        }

        reconciliationResultRepository.saveAll(results);

        int discrepancyCount = results.size();
        Long clearingAmount = null;
        ClearingBatch clearingBatch = null;
        if (discrepancyCount == 0 && fileNet > 0) {
            List<PaymentTransaction> succeededTxs = txs.stream()
                    .filter(t -> t.getStatus() == TransactionStatus.SUCCEEDED)
                    .toList();
            clearingBatch = clearingService.clearForReconciliation(batch, fileNet, succeededTxs);
            clearingAmount = fileNet;
        }
        batch.markCompleted(discrepancyCount, clearingAmount, autoResolvedCount);

        return new ValidateReconciliationResponse(
                batchId,
                batch.getStatus().name(),
                autoResolvedCount,
                counts.missingOnCard,
                counts.missingOnPg,
                counts.amountMismatch,
                counts.statusMismatch,
                counts.aggregate,
                clearingBatch != null ? clearingBatch.getId() : null,
                clearingBatch != null ? clearingBatch.getBatchCode() : null
        );
    }

    private int reconcileUnknowns(
            Instant rangeStart,
            Instant rangeEnd,
            List<StagingSettlement> stagings,
            CardCompany cardCompany
    ) {
        List<PaymentTransaction> unknownTxs = paymentTransactionRepository.findForReconciliation(
                RECON_TYPES, UNKNOWN_STATUS, rangeStart, rangeEnd, cardCompany
        );
        if (unknownTxs.isEmpty()) {
            return 0;
        }

        Map<String, StagingSettlement> stagingByCardRequestRef = new HashMap<>();
        for (StagingSettlement s : stagings) {
            stagingByCardRequestRef.put(s.getCardRequestRef(), s);
        }

        int resolved = 0;
        for (PaymentTransaction tx : unknownTxs) {
            StagingSettlement s = stagingByCardRequestRef.get(tx.getCardRequestRef());
            if (s == null) {
                continue;
            }
            SettlementType expectedType = tx.getType() == TransactionType.CAPTURE
                    ? SettlementType.CAPTURE
                    : SettlementType.REFUND;
            if (s.getTxType() != expectedType) {
                continue;
            }
            if (s.getTxStatus() == SettlementStatus.APPROVED
                    && !s.getAmount().equals(tx.getAmount())) {
                continue;
            }

            try {
                if (s.getTxStatus() == SettlementStatus.APPROVED) {
                    if (tx.getType() == TransactionType.CAPTURE) {
                        unknownReconciler.reconcileCaptureApproved(tx.getId(), s.getApprovalNo());
                    } else {
                        unknownReconciler.reconcileRefundApproved(tx.getId(), s.getApprovalNo());
                    }
                } else {
                    if (tx.getType() == TransactionType.CAPTURE) {
                        unknownReconciler.reconcileCaptureDeclined(tx.getId(), s.getApprovalNo());
                    } else {
                        unknownReconciler.reconcileRefundDeclined(tx.getId(), s.getApprovalNo());
                    }
                }
                resolved++;
            } catch (Exception e) {
                log.warn("unknown reconcile failed for txId={} (cardRequestRef={}): {}",
                        tx.getId(), tx.getCardRequestRef(), e.toString());
            }
        }

        return resolved;
    }

    private long computeLedgerNet(List<Long> accountIds, Instant rangeStart, Instant rangeEnd) {
        long debitSum = ledgerRepository.sumDirectionalAmount(
                accountIds, RECON_LEDGER_TYPES, LedgerDirection.DEBIT, rangeStart, rangeEnd
        );
        long creditSum = ledgerRepository.sumDirectionalAmount(
                accountIds, RECON_LEDGER_TYPES, LedgerDirection.CREDIT, rangeStart, rangeEnd
        );
        return debitSum - creditSum;
    }

    private long computeFileNet(List<StagingSettlement> stagings) {
        long net = 0;
        for (StagingSettlement s : stagings) {
            if (s.getTxStatus() != SettlementStatus.APPROVED) {
                continue;
            }
            net += s.getTxType() == SettlementType.CAPTURE ? s.getAmount() : -s.getAmount();
        }
        return net;
    }

    private static class Counts {
        int missingOnCard;
        int missingOnPg;
        int amountMismatch;
        int statusMismatch;
        int aggregate;
    }
}
