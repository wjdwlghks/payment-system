package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.ReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {

    long countByReconBatchId(Long reconBatchId);
}
