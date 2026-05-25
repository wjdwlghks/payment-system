package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findByPaymentIntent(PaymentIntent paymentIntent);
    Optional<Refund> findByRefundKey(String refundKey);
    Optional<Refund> findByTransactionId(Long transactionId);
}
