package com.example.paymentsystem.card.repository;

import com.example.paymentsystem.card.domain.CardCapture;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardCaptureRepository extends JpaRepository<CardCapture, Long> {

    Optional<CardCapture> findByCardRequestRef(String cardRequestRef);

    List<CardCapture> findByStatusIn(List<CardCaptureStatus> statuses);

    @Query("SELECT c.cardRequestRef FROM CardCapture c WHERE c.status = :status")
    List<String> findCardRequestRefsByStatus(@Param("status") CardCaptureStatus status);
}
