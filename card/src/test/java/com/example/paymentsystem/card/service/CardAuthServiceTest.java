package com.example.paymentsystem.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.paymentsystem.card.domain.CardAuthorization;
import com.example.paymentsystem.card.domain.CardAuthorizationStatus;
import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.dto.CardAuthResponse;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CardAuthServiceTest {

    private final CardAuthorizationRepository cardAuthorizationRepository =
            org.mockito.Mockito.mock(CardAuthorizationRepository.class);
    private final CardAuthService cardAuthService = new CardAuthService(cardAuthorizationRepository);

    @Test
    void createsAuthorizedCardAuthorization() {
        when(cardAuthorizationRepository.save(any(CardAuthorization.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CardAuthResponse response = cardAuthService.authorize(new CardAuthRequest(
                "payment-key",
                "order-12345",
                "merchant-1",
                50_000L
        ));

        ArgumentCaptor<CardAuthorization> captor = ArgumentCaptor.forClass(CardAuthorization.class);
        org.mockito.Mockito.verify(cardAuthorizationRepository).save(captor.capture());
        CardAuthorization authorization = captor.getValue();

        assertThat(response.success()).isTrue();
        assertThat(response.externalId()).startsWith("auth-");
        assertThat(response.authorizedAt()).isNotNull();
        assertThat(authorization.getAuthId()).isEqualTo(response.externalId());
        assertThat(authorization.getAuthIdempotentKey()).isEqualTo("payment-key:auth");
        assertThat(authorization.getCaptureIdempotencyKey()).isNull();
        assertThat(authorization.getAmount()).isEqualTo(50_000L);
        assertThat(authorization.getStatus()).isEqualTo(CardAuthorizationStatus.AUTHORIZED);
        assertThat(authorization.getAuthorizedAt()).isEqualTo(response.authorizedAt());
        assertThat(authorization.getCapturedAt()).isNull();
    }
}
