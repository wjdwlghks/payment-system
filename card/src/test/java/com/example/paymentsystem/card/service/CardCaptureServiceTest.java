package com.example.paymentsystem.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardAuthorizationStatus;
import com.example.paymentsystem.card.dto.CardCaptureRequest;
import com.example.paymentsystem.card.dto.CardCaptureResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CardCaptureServiceTest {

    private final CardAuthorizationRepository cardAuthorizationRepository =
            org.mockito.Mockito.mock(CardAuthorizationRepository.class);
    private final CardCaptureService cardCaptureService = new CardCaptureService(cardAuthorizationRepository);

    @Test
    void updatesAuthorizationAsCaptured() {
        CardAuthorization authorization = new CardAuthorization(
                "auth-123",
                "payment-key",
                50_000L,
                Instant.parse("2026-04-23T10:15:30.123Z")
        );
        when(cardAuthorizationRepository.findByAuthId("auth-123")).thenReturn(Optional.of(authorization));

        CardCaptureResponse response = cardCaptureService.capture(
                "auth-123",
                new CardCaptureRequest("payment-key", "order-12345", 50_000L)
        );

        assertThat(response.success()).isTrue();
        assertThat(response.externalId()).startsWith("capture-");
        assertThat(authorization.getStatus()).isEqualTo(CardAuthorizationStatus.CAPTURED);
        assertThat(authorization.getCaptureIdempotencyKey()).isEqualTo("payment-key:capture");
        assertThat(authorization.getCapturedAt()).isNotNull();
    }
}
