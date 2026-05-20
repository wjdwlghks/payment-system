package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;

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
}
