package com.example.paymentsystem.card.repository;

import com.example.paymentsystem.card.domain.CardAuthorization;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardAuthorizationRepository extends JpaRepository<CardAuthorization, Long> {

    Optional<CardAuthorization> findByAuthId(String authId);
    Optional<CardAuthorization> findByAuthIdempotentKey(String authIdempotentKey);
}
