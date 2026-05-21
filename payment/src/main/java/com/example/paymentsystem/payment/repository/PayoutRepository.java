package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findTop1ByPayoutCodeStartingWithOrderByPayoutCodeDesc(String payoutCodePrefix);
}
