package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPaymentKey(String paymentKey);

    Optional<PaymentIntent> findByMerchantIdAndOrderId(String merchantId, String orderId);

    List<PaymentIntent> findTop300ByStatusOrderByUpdatedAtAsc(PaymentIntentStatus status);

    long countByStatus(PaymentIntentStatus status);

    /**
     * 만료 대상. {@code updatedAt}을 기준으로 삼는 이유는 {@code FDS_PASSED}가 이 intent를
     * 건드리는 <b>마지막 쓰기</b>라, 그 값이 곧 "승인을 기다리기 시작한 시각"이기 때문이다.
     * 승인이 시작되면 상태가 바뀌므로 이 쿼리에서 자연히 빠진다.
     */
    @Query("""
    SELECT pi.id FROM PaymentIntent pi
    WHERE pi.status = :status
      AND pi.updatedAt < :threshold
    ORDER BY pi.updatedAt ASC
    """)
    List<Long> findExpirableIds(
            @Param("status") PaymentIntentStatus status,
            @Param("threshold") Instant threshold,
            Limit limit
    );
}
