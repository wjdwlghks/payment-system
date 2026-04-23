package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPaymentKey(String paymentKey);
}
