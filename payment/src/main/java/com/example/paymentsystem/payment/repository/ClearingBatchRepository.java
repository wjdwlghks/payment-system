package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.ClearingBatch;
import com.example.paymentsystem.payment.domain.ClearingBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClearingBatchRepository extends JpaRepository<ClearingBatch, Long> {

    Optional<ClearingBatch> findTop1ByStatusOrderByWindowEndDesc(ClearingBatchStatus status);

    Optional<ClearingBatch> findTop1ByBatchCodeStartingWithOrderByBatchCodeDesc(String batchCodePrefix);
}
