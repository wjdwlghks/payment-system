package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.SettlementRunItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementItemRepository extends JpaRepository<SettlementRunItem, Long> {
}
