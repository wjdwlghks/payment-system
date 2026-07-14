package com.example.paymentsystem.card.repository;

import com.example.paymentsystem.card.domain.CardRefund;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRefundRepository extends JpaRepository<CardRefund, Long> {

    Optional<CardRefund> findByCardRequestRef(String cardRequestRef);
}
