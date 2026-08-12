package com.example.paymentsystem.card.repository;

import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardAuthentication;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardAuthenticationRepository extends JpaRepository<CardAuthentication, Long> {

    Optional<CardAuthentication> findByAuthId(String authId);
    Optional<CardAuthentication> findByCardRequestRef(String cardRequestRef);
    Optional<CardAuthentication> findByApprovalCardRequestRef(String approvalCardRequestRef);
    List<CardAuthentication> findByApprovalStatusIn(List<CardApprovalStatus> statuses);

    @Query("SELECT a.cardRequestRef FROM CardAuthentication a WHERE a.authStatus = :status")
    List<String> findAuthCardRequestRefsByStatus(@Param("status") CardAuthStatus status);

    @Query("SELECT a.approvalCardRequestRef FROM CardAuthentication a WHERE a.approvalStatus = :status AND a.approvalCardRequestRef IS NOT NULL")
    List<String> findApprovalCardRequestRefsByStatus(@Param("status") CardApprovalStatus status);

}
