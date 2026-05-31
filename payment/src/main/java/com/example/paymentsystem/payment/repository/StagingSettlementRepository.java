package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.StagingSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagingSettlementRepository extends JpaRepository<StagingSettlement, Long> {
}
