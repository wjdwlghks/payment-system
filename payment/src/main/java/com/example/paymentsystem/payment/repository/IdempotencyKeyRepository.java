package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByIdempotentKeyAndOperation(String idempotentKey, IdempotencyOperation operation);

    List<IdempotencyKey> findTop3ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            IdempotentStatus status,
            Instant updatedAtBefore
    );
}
