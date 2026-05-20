package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.ClearingBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClearingBatchItemRepository extends JpaRepository<ClearingBatchItem, Long> {
}
