package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.SettlementRun;
import com.example.paymentsystem.payment.domain.SettlementRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {
    Optional<SettlementRun> findTop1ByStatusOrderByWindowEndDesc(SettlementRunStatus settlementRunStatus);

    Optional<SettlementRun> findTop1ByRunCodeStartingWithOrderByRunCodeDesc(String runCodePrefix);
}
