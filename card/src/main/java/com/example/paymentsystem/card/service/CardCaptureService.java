package com.example.paymentsystem.card.service;

import com.example.paymentsystem.card.dto.CardCaptureRequest;
import com.example.paymentsystem.card.dto.CardCaptureResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class CardCaptureService {

    public CardCaptureResponse capture(String authorizationId, CardCaptureRequest request) {
        boolean success = ThreadLocalRandom.current().nextBoolean();
        return new CardCaptureResponse(success, "capture-" + UUID.randomUUID());
    }
}
