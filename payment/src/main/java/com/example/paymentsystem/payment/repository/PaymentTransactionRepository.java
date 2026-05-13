package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByPaymentIntentAndTypeAndStatus(
            PaymentIntent paymentIntent,
            TransactionType type,
            TransactionStatus status
    );

    List<PaymentTransaction> findTop3ByStatusOrderByUpdatedAtAsc(TransactionStatus status);
}
