package com.example.paymentsystem.card.repository;

import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardAuthorizationRepository extends JpaRepository<CardAuthorization, Long> {

    Optional<CardAuthorization> findByAuthId(String authId);
    Optional<CardAuthorization> findByCardRequestRef(String cardRequestRef);
    Optional<CardAuthorization> findByCaptureCardRequestRef(String captureCardRequestRef);
    List<CardAuthorization> findByCaptureStatusIn(List<CardCaptureStatus> statuses);

    @Query("SELECT a.cardRequestRef FROM CardAuthorization a WHERE a.authStatus = :status")
    List<String> findAuthCardRequestRefsByStatus(@Param("status") CardAuthStatus status);

    @Query("SELECT a.captureCardRequestRef FROM CardAuthorization a WHERE a.captureStatus = :status AND a.captureCardRequestRef IS NOT NULL")
    List<String> findCaptureCardRequestRefsByStatus(@Param("status") CardCaptureStatus status);

}
