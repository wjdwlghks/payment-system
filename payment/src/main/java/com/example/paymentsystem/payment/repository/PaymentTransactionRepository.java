package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.*;

import java.time.Instant;
import java.util.Collection;
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

    List<PaymentTransaction> findTop30ByStatusOrderByUpdatedAtAsc(TransactionStatus status);

    List<PaymentTransaction> findTop30ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            TransactionStatus status,
            Instant updatedAtBefore
    );

    @Query("SELECT t FROM PaymentTransaction t JOIN FETCH t.paymentIntent WHERE t.status = :status ORDER BY t.updatedAt ASC LIMIT 300")
    List<PaymentTransaction> findTop300WithIntentByStatusOrderByUpdatedAtAsc(@Param("status") TransactionStatus status);

    @Query("SELECT t FROM PaymentTransaction t JOIN FETCH t.paymentIntent WHERE t.status = :status AND t.updatedAt < :before ORDER BY t.updatedAt ASC LIMIT 300")
    List<PaymentTransaction> findTop300WithIntentByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            @Param("status") TransactionStatus status,
            @Param("before") Instant before
    );

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

    @Query("""
    select t
    from PaymentTransaction t
    where t.type in :types
      and t.status in :statuses
      and t.createdAt >= :rangeStart
      and t.createdAt < :rangeEnd
      and t.paymentIntent.cardCompany = :cardCompany
      and not exists (
          select 1
          from ClearingBatchItem item
          where item.transaction = t
      )
""")
    List<PaymentTransaction> findForReconciliation(
            @Param("types") Collection<TransactionType> types,
            @Param("statuses") Collection<TransactionStatus> statuses,
            @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd,
            @Param("cardCompany") CardCompany cardCompany
    );

    long countByStatus(TransactionStatus status);

    long countByStatusAndUpdatedAtBefore(TransactionStatus status, Instant updatedAtBefore);

    @Query("SELECT t.cardRequestRef FROM PaymentTransaction t WHERE t.type = :type AND t.status = :status")
    List<String> findCardRequestRefsByTypeAndStatus(
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status
    );

    @Query("""
    SELECT COUNT(pi) FROM PaymentIntent pi
    WHERE pi.status = com.example.paymentsystem.payment.domain.PaymentIntentStatus.DONE
    AND NOT EXISTS (
        SELECT pt FROM PaymentTransaction pt
        WHERE pt.paymentIntent = pi
          AND pt.type = com.example.paymentsystem.payment.domain.TransactionType.CAPTURE
          AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
    )
    """)
    long countDoneWithoutCaptureSucceeded();

    @Query("""
    SELECT COUNT(pt) FROM PaymentTransaction pt
    WHERE pt.type = com.example.paymentsystem.payment.domain.TransactionType.CAPTURE
      AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
      AND pt.paymentIntent.status NOT IN (
          com.example.paymentsystem.payment.domain.PaymentIntentStatus.DONE,
          com.example.paymentsystem.payment.domain.PaymentIntentStatus.REFUNDED,
          com.example.paymentsystem.payment.domain.PaymentIntentStatus.PARTIALLY_REFUNDED,
          com.example.paymentsystem.payment.domain.PaymentIntentStatus.CANCELLED
      )
    """)
    long countCaptureSucceededWithoutDone();
}
