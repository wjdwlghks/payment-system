package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.StagingFailed;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagingFailedRepository extends JpaRepository<StagingFailed, Long> {
}
