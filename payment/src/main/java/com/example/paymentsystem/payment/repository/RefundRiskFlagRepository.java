package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.RefundRiskFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRiskFlagRepository extends JpaRepository<RefundRiskFlag, Long> {
}
