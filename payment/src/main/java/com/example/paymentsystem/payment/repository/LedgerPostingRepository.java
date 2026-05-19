package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.LedgerPosting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, Long> {
}
