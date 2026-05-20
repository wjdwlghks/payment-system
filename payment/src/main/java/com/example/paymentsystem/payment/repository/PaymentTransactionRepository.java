package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByPaymentIntentAndTypeAndStatus(
            PaymentIntent paymentIntent,
            TransactionType type,
            TransactionStatus status
    );

    List<PaymentTransaction> findTop3ByStatusOrderByUpdatedAtAsc(TransactionStatus status);

    @Query("""
    select t
    from PaymentTransaction t
    where t.status = :status
      and t.type = :type
      and t.updatedAt >= :windowStart
      and t.updatedAt < :windowEnd
      and not exists (
          select 1
          from ClearingBatchItem item
          where item.transaction = t
      )
    order by t.updatedAt asc
""")
    List<PaymentTransaction> findUnclearedCaptureTransactions(
            @Param("status") TransactionStatus status,
            @Param("type") TransactionType type,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );


    @Query("""
    select t
    from PaymentTransaction t
    where t.status = :status
      and t.type = :type
      and not exists (
          select 1
          from ClearingBatchItem item
          where item.transaction = t
      )
    order by t.updatedAt asc
""")
    List<PaymentTransaction> findOldestUnclearedTransactions(
            @Param("status") TransactionStatus status,
            @Param("type") TransactionType type,
            Pageable pageable
    );

    @Query("""
    select t
    from ClearingBatchItem item
    join item.transaction t
    join item.batch batch
    where t.status = :txStatus
      and t.type = :type
      and batch.status = :batchStatus
      and not exists (
          select 1
          from SettlementRunItem item
          where item.captureTransaction = t
      )
    order by t.updatedAt asc
""")
    List<PaymentTransaction> findOldestClearedTransactions(
            @Param("txStatus") TransactionStatus txStatus,
            @Param("type") TransactionType type,
            @Param("batchStatus") ClearingBatchStatus batchStatus,
            Pageable pageable
    );

    @Query("""
    select t
    from ClearingBatchItem item
    join item.transaction t
    join item.batch batch
    where t.status = :txStatus
      and t.type = :type
      and batch.status = :batchStatus
      and t.updatedAt >= :windowStart
      and t.updatedAt < :windowEnd
      and not exists (
          select 1
          from SettlementRunItem item
          where item.captureTransaction = t
      )
    order by t.updatedAt asc
""")
    List<PaymentTransaction> findClearedTransactions(
            @Param("txStatus") TransactionStatus txStatus,
            @Param("type") TransactionType type,
            @Param("batchStatus") ClearingBatchStatus batchStatus,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );
}
