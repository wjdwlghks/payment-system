package com.example.paymentsystem.card.repository;

import com.example.paymentsystem.card.domain.CardCancel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardCancelRepository extends JpaRepository<CardCancel, Long> {

    boolean existsByApprovalId(String approvalId);

    Optional<CardCancel> findByApprovalId(String approvalId);
}
