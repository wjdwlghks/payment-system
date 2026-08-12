package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPaymentKey(String paymentKey);

    Optional<PaymentIntent> findByMerchantIdAndOrderId(String merchantId, String orderId);

    List<PaymentIntent> findTop300ByStatusOrderByUpdatedAtAsc(PaymentIntentStatus status);

    long countByStatus(PaymentIntentStatus status);

}
