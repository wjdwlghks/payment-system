package com.example.paymentsystem.fds.repository;

import com.example.paymentsystem.fds.domain.FraudCheck;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCheckRepository extends JpaRepository<FraudCheck, Long> {
}
