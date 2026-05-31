package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.ReconBatch;
import com.example.paymentsystem.payment.domain.StagingFailed;
import com.example.paymentsystem.payment.domain.StagingSettlement;
import com.example.paymentsystem.payment.dto.ParsedSettlementRow;
import com.example.paymentsystem.payment.repository.ReconBatchRepository;
import com.example.paymentsystem.payment.repository.StagingFailedRepository;
import com.example.paymentsystem.payment.repository.StagingSettlementRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ChunkProcessor {

    private final ReconBatchRepository reconBatchRepository;
    private final StagingSettlementRepository stagingSettlementRepository;
    private final StagingFailedRepository stagingFailedRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconBatch createBatch(String cardCompany, LocalDate businessDate) {
        return reconBatchRepository.save(new ReconBatch(cardCompany, businessDate));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveChunk(Long batchId, List<ParsedSettlementRow> rows) {
        ReconBatch batchRef = entityManager.getReference(ReconBatch.class, batchId);
        List<StagingSettlement> entities = new ArrayList<>(rows.size());
        for (ParsedSettlementRow row : rows) {
            entities.add(toStagingEntity(batchRef, row));
        }
        stagingSettlementRepository.saveAll(entities);
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOne(Long batchId, ParsedSettlementRow row) {
        ReconBatch batchRef = entityManager.getReference(ReconBatch.class, batchId);
        stagingSettlementRepository.save(toStagingEntity(batchRef, row));
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantineRows(Long batchId, List<ParsedSettlementRow> rows, String failureReason) {
        ReconBatch batchRef = entityManager.getReference(ReconBatch.class, batchId);
        List<StagingFailed> entities = new ArrayList<>(rows.size());
        for (ParsedSettlementRow row : rows) {
            entities.add(new StagingFailed(batchRef, row, failureReason));
        }
        stagingFailedRepository.saveAll(entities);
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantineOne(Long batchId, ParsedSettlementRow row, String failureReason) {
        ReconBatch batchRef = entityManager.getReference(ReconBatch.class, batchId);
        stagingFailedRepository.save(new StagingFailed(batchRef, row, failureReason));
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIngested(Long batchId, int rowCount, long fileTotalAmount) {
        ReconBatch batch = reconBatchRepository.findById(batchId).orElseThrow();
        batch.markIngested(rowCount, fileTotalAmount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIngestedPartial(Long batchId, int rowCount, long fileTotalAmount) {
        ReconBatch batch = reconBatchRepository.findById(batchId).orElseThrow();
        batch.markIngestedPartial(rowCount, fileTotalAmount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAborted(Long batchId) {
        ReconBatch batch = reconBatchRepository.findById(batchId).orElseThrow();
        batch.markAborted();
    }

    private static StagingSettlement toStagingEntity(ReconBatch batchRef, ParsedSettlementRow row) {
        return new StagingSettlement(
                batchRef,
                row.approvalNo(),
                row.amount(),
                row.transactedAt(),
                row.txType(),
                row.txStatus(),
                row.originalApprovalNo()
        );
    }
}
