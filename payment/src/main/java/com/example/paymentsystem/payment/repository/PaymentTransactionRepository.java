package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.*;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByPaymentIntentAndTypeAndStatus(
            PaymentIntent paymentIntent,
            TransactionType type,
            TransactionStatus status
    );

    /**
     * 조회할 때가 된 UNKNOWN 트랜잭션.
     *
     * <p>LIMIT은 처리율 상한이 아니라 <b>페이지 크기</b>다 — 호출부가 due가 빌 때까지 반복해서
     * 가져간다. 정렬 키가 updated_at이 아니라 next_inquiry_at인 것이 핵심으로,
     * 이 값은 시도할 때마다 전진하므로 특정 행이 맨 앞을 영구 점유할 수 없다.
     */
    @Query("""
    SELECT t FROM PaymentTransaction t JOIN FETCH t.paymentIntent
    WHERE t.status = com.example.paymentsystem.payment.domain.TransactionStatus.UNKNOWN
      AND t.nextInquiryAt <= :now
    ORDER BY t.nextInquiryAt ASC
    """)
    List<PaymentTransaction> findDueUnknown(@Param("now") Instant now, Limit limit);

    /**
     * 크래시로 REQUESTED에 방치된 트랜잭션.
     *
     * <p>첫 조회 자격은 여전히 {@code updated_at}이 정한다(60초 이상 무응답). 한 번 시도한
     * 뒤부터는 next_inquiry_at이 채워지므로 그때부터 백오프가 적용된다 — 그래서 조건이 OR다.
     */
    @Query("""
    SELECT t FROM PaymentTransaction t JOIN FETCH t.paymentIntent
    WHERE t.status = com.example.paymentsystem.payment.domain.TransactionStatus.REQUESTED
      AND t.updatedAt < :staleBefore
      AND (t.nextInquiryAt IS NULL OR t.nextInquiryAt <= :now)
    ORDER BY COALESCE(t.nextInquiryAt, t.updatedAt) ASC
    """)
    List<PaymentTransaction> findDueStaleRequested(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            Limit limit
    );

    /**
     * 선점(claim). 가져온 페이지의 다음 조회 시각을 한 번에 밀어놓는다.
     *
     * <p>fan-out <b>전에</b> 커밋해야 한다. 태스크 안에서 각자 스탬프하면 executor 종료·예외·
     * 예산 초과로 시작조차 못 한 태스크의 행이 스탬프 없이 남아, 막으려던 영구 점유가 재발한다.
     * 스탬프는 보장돼야 하고 태스크 실행은 보장되지 않는다.
     *
     * <p>{@code nextInquiryAt <= now} 조건은 그 사이 다른 주체가 이미 집어간 행을 건너뛰게 한다.
     * 몇 건을 선점했는지는 알려주지만 어느 것인지는 알려주지 않으므로, 다중 인스턴스로 가면
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}로 바꿔야 한다.
     */
    @Modifying
    @Query("""
    UPDATE PaymentTransaction t
       SET t.inquiryAttempts = t.inquiryAttempts + 1,
           t.nextInquiryAt = :nextDue
     WHERE t.id IN :ids
       AND (t.nextInquiryAt IS NULL OR t.nextInquiryAt <= :now)
    """)
    int claimForInquiry(
            @Param("ids") Collection<Long> ids,
            @Param("nextDue") Instant nextDue,
            @Param("now") Instant now
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
    long countApprovedIntentWithoutApproveTx();

    @Query("""
    SELECT COUNT(pt) FROM PaymentTransaction pt
    WHERE pt.type = com.example.paymentsystem.payment.domain.TransactionType.APPROVE
      AND pt.status = com.example.paymentsystem.payment.domain.TransactionStatus.SUCCEEDED
      AND pt.paymentIntent.status <> com.example.paymentsystem.payment.domain.PaymentIntentStatus.APPROVED
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
