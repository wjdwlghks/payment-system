package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.CaptureBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureBatchRepository extends JpaRepository<CaptureBatch, Long> {
    Optional<CaptureBatch> findTop1ByBatchCodeStartingWithOrderByBatchCodeDesc(String prefix);
}
