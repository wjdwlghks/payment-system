package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.ReconBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconBatchRepository extends JpaRepository<ReconBatch, Long> {

    @Query("SELECT COALESCE(SUM(b.autoResolvedCount), 0) FROM ReconBatch b")
    long sumAutoResolvedCount();

    @Query("SELECT COALESCE(SUM(b.discrepancyCount), 0) FROM ReconBatch b")
    long sumDiscrepancyCount();
}
