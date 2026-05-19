package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

}
