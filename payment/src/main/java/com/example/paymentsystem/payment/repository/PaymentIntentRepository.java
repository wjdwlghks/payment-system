package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.Query;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPaymentKey(String paymentKey);

    Optional<PaymentIntent> findByMerchantIdAndOrderId(String merchantId, String orderId);

    List<PaymentIntent> findTop300ByStatusOrderByUpdatedAtAsc(PaymentIntentStatus status);

    long countByStatus(PaymentIntentStatus status);

    /** 매입 대상: 승인이 확정(DONE)됐는데 아직 매입 tx가 하나도 없는 결제. */
    @Query("""
    SELECT pi.id FROM PaymentIntent pi
    WHERE pi.status = com.example.paymentsystem.payment.domain.PaymentIntentStatus.DONE
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
    )
    ORDER BY pi.id ASC
    """)
    List<Long> findCaptureTargetIds(Pageable pageable);
}
