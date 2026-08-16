package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.dto.PendingInquiry;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByPaymentIntentAndTypeAndStatus(
            PaymentIntent paymentIntent,
            TransactionType type,
            TransactionStatus status
    );

    /**
     * 상태를 가리지 않고 한 건을 찾는다. <b>CAPTURE·CANCEL 전용</b>이다 — 이 둘만 결제당
     * 최대 1행이 보장된다(CAPTURE는 {@code uk_transaction_capture_intent}, CANCEL은
     * {@code PAYMENT_CANCEL} 멱등키). AUTH/FDS/APPROVE에 쓰면 여러 행이 걸려 터진다.
     */
    Optional<PaymentTransaction> findByPaymentIntentAndType(
            PaymentIntent paymentIntent,
            TransactionType type
    );

    /** 큐 소비자가 한 건을 다룰 때 쓰는 조회 — intent까지 붙여 detach 후에도 카드사를 읽을 수 있게. */
    @Query("SELECT t FROM PaymentTransaction t JOIN FETCH t.paymentIntent WHERE t.id = :id")
    Optional<PaymentTransaction> findWithIntentById(@Param("id") Long id);

    /**
     * 큐가 놓친 건. due 시각이 유예를 넘겨 지났다는 건 인메모리 큐가 그 건을 잃었다는 뜻이다
     * (프로세스 재시작, 적재 실패 등). sweeper가 이걸 주워 다시 큐에 넣는다.
     */
    @Query("""
    SELECT t.id FROM PaymentTransaction t
    WHERE t.status = com.example.paymentsystem.payment.domain.TransactionStatus.UNKNOWN
      AND t.nextInquiryAt <= :overdueBefore
    ORDER BY t.nextInquiryAt ASC
    """)
    List<Long> findOverdueUnknownIds(@Param("overdueBefore") Instant overdueBefore, Limit limit);

    /** 재기동 복원용 — 남은 지연을 그대로 살려 큐에 다시 넣는다. */
    @Query("""
    SELECT new com.example.paymentsystem.payment.dto.PendingInquiry(t.id, t.nextInquiryAt)
    FROM PaymentTransaction t
    WHERE t.status = com.example.paymentsystem.payment.domain.TransactionStatus.UNKNOWN
    ORDER BY t.nextInquiryAt ASC
    """)
    List<PendingInquiry> findAllPendingInquiries(Limit limit);

    /** 크래시로 REQUESTED에 방치된 건의 id. 이벤트가 없는 상태라 스캔으로만 찾을 수 있다. */
    @Query("""
    SELECT t.id FROM PaymentTransaction t
    WHERE t.status = com.example.paymentsystem.payment.domain.TransactionStatus.REQUESTED
      AND t.updatedAt < :staleBefore
    ORDER BY t.updatedAt ASC
    """)
    List<Long> findStaleRequestedIds(@Param("staleBefore") Instant staleBefore, Limit limit);

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
    long countApprovedIntentWithoutApproveTx();

    /**
     * 승인 tx는 성공했는데 intent가 그걸 반영하지 않았다 — 항상 0이어야 한다.
     *
     * <p>{@code CANCELED}를 허용 집합에 넣는 이유는 취소가 승인을 <b>지우는 게 아니라
     * 그 뒤에 오는 사건</b>이기 때문이다. 취소된 결제도 승인은 실제로 있었다.
     * 이걸 빼면 취소 한 건마다 불변식이 깨졌다고 보고한다.
     */
    @Query("""
    SELECT COUNT(pt) FROM PaymentTransaction pt
    WHERE pt.type = com.example.paymentsystem.payment.domain.TransactionType.APPROVE
      AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
      AND pt.paymentIntent.status NOT IN (
            com.example.paymentsystem.payment.domain.PaymentIntentStatus.APPROVED,
            com.example.paymentsystem.payment.domain.PaymentIntentStatus.CANCELED
      )
    """)
    long countApproveTxWithoutApprovedIntent();

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
