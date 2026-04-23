package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.dto.CardAuthResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class CardAuthService {

    public CardAuthResponse authorize(CardAuthRequest request) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        return new CardAuthResponse(success, "auth-" + UUID.randomUUID(), success ? Instant.now() : null);
    }
}
