package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.WebhookOutbox;
import com.example.paymentsystem.payment.domain.WebhookOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long> {

    List<WebhookOutbox> findTop30ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            WebhookOutboxStatus status,
            Instant nextAttemptAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WebhookOutbox w where w.id = :id")
    WebhookOutbox findByIdForUpdate(Long id);

    long countByStatus(WebhookOutboxStatus status);
}
