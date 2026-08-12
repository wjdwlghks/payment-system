package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.CaptureBatchItem;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureBatchItemRepository extends JpaRepository<CaptureBatchItem, Long> {

    @Query("SELECT i.transaction FROM CaptureBatchItem i WHERE i.batch.id = :batchId")
    List<PaymentTransaction> findTransactionsByBatchId(@Param("batchId") Long batchId);
}
