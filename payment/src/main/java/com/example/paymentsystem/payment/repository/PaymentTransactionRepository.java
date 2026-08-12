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
    WHERE pi.status = com.example.paymentsystem.payment.domain.PaymentIntentStatus.APPROVED
    AND NOT EXISTS (
        SELECT pt FROM PaymentTransaction pt
        WHERE pt.paymentIntent = pi
          AND pt.type = com.example.paymentsystem.payment.domain.TransactionType.APPROVE
          AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
    )
    """)
    long countDoneWithoutApproveSucceeded();

    @Query("""
    SELECT COUNT(pt) FROM PaymentTransaction pt
    WHERE pt.type = com.example.paymentsystem.payment.domain.TransactionType.APPROVE
      AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
      AND pt.paymentIntent.status <> com.example.paymentsystem.payment.domain.PaymentIntentStatus.APPROVED
    """)
    long countApproveSucceededWithoutDone();

    /** 매입은 됐는데 그 결제에 성공한 승인이 없다 — 고아 매입. 항상 0이어야 한다. */
    @Query("""
    SELECT COUNT(pt) FROM PaymentTransaction pt
    WHERE pt.type = com.example.paymentsystem.payment.domain.TransactionType.CAPTURE
      AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
      AND NOT EXISTS (
        SELECT ap FROM PaymentTransaction ap
        WHERE ap.paymentIntent = pt.paymentIntent
          AND ap.type = com.example.paymentsystem.payment.domain.TransactionType.APPROVE
          AND ap.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
      )
    """)
    long countCaptureWithoutApprove();

    /** 승인은 끝났는데 아직 매입이 안 됐다 — 매입 배치 실행 전에는 정상값이다(판정에 쓰지 않는다). */
    @Query("""
    SELECT COUNT(pi) FROM PaymentIntent pi
    WHERE pi.status = com.example.paymentsystem.payment.domain.PaymentIntentStatus.APPROVED
    AND EXISTS (
        SELECT ap FROM PaymentTransaction ap
        WHERE ap.paymentIntent = pi
          AND ap.type = com.example.paymentsystem.payment.domain.TransactionType.APPROVE
          AND ap.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
    )
    AND NOT EXISTS (
        SELECT cp FROM PaymentTransaction cp
        WHERE cp.paymentIntent = pi
          AND cp.type = com.example.paymentsystem.payment.domain.TransactionType.CAPTURE
          AND cp.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
    )
    """)
    long countApprovedWithoutCapture();
}
