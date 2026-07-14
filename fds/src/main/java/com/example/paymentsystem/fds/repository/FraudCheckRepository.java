package com.example.paymentsystem.fds.repository;

import com.example.paymentsystem.fds.domain.FraudCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FraudCheckRepository extends JpaRepository<FraudCheck, Long> {
    Optional<FraudCheck> findByRequestRef(String requestRef);
}
