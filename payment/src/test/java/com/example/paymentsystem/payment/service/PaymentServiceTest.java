package com.example.paymentsystem.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.paymentsystem.payment.client.CardAuthRequest;
import com.example.paymentsystem.payment.client.CardAuthResponse;
import com.example.paymentsystem.payment.client.CardCaptureResponse;
import com.example.paymentsystem.payment.client.CardClient;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.client.fds.FdsClient;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.dto.PaymentRequest;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentServiceTest {

    private final PaymentCommandService paymentCommandService = org.mockito.Mockito.mock(PaymentCommandService.class);
    private final CardClient cardClient = org.mockito.Mockito.mock(CardClient.class);
    private final FdsClient fdsClient = org.mockito.Mockito.mock(FdsClient.class);
    private final PaymentService paymentService = new PaymentService(
            paymentCommandService,
            cardClient,
            fdsClient
    );

    @Test
    void callsCardAuthOutsideCommandTransactionAndCompletesAuth() {
        PaymentRequest request = new PaymentRequest("order-12345", "merchant-1", 50_000L);
        AuthRequestContext context = new AuthRequestContext(
                1L,
                10L,
                "payment-key",
                "order-12345",
                "merchant-1",
                50_000L
        );
        Instant authorizedAt = Instant.parse("2026-04-23T10:15:30.123Z");
        CardAuthResponse cardResponse = new CardAuthResponse(true, "auth-123", authorizedAt);
        PaymentResponse expectedResponse = new PaymentResponse(
                "payment-key",
                "order-12345",
                PaymentIntentStatus.AUTH_READY,
                50_000L,
                authorizedAt
        );

        when(paymentCommandService.createAuthRequest(request, "idem-1")).thenReturn(context);
        when(cardClient.authorize(any())).thenReturn(cardResponse);
        when(paymentCommandService.completeAuth(1L, 10L, cardResponse)).thenReturn(expectedResponse);

        PaymentResponse response = paymentService.requestPayment(request, "idem-1");

        ArgumentCaptor<CardAuthRequest> cardRequestCaptor = ArgumentCaptor.forClass(CardAuthRequest.class);
        verify(paymentCommandService).createAuthRequest(request, "idem-1");
        verify(cardClient).authorize(cardRequestCaptor.capture());
        verify(paymentCommandService).completeAuth(1L, 10L, cardResponse);
        assertThat(cardRequestCaptor.getValue().paymentKey()).isEqualTo("payment-key");
        assertThat(response).isEqualTo(expectedResponse);
    }

    @Test
    void stopsConfirmWhenFdsFails() {
        FdsRequestContext context = new FdsRequestContext(
                1L,
                20L,
                "payment-key",
                "order-12345",
                "merchant-1",
                50_000L
        );
        FdsCheckResponse fdsResponse = new FdsCheckResponse(false, "REJECT", "fds-123");
        PaymentResponse expectedResponse = new PaymentResponse(
                "payment-key",
                "order-12345",
                PaymentIntentStatus.FDS_FAILED,
                50_000L,
                Instant.parse("2026-04-23T10:15:30.123Z")
        );

        when(paymentCommandService.createFdsRequest("payment-key")).thenReturn(context);
        when(fdsClient.check(any())).thenReturn(fdsResponse);
        when(paymentCommandService.failFds(1L, 20L, fdsResponse)).thenReturn(expectedResponse);

        PaymentResponse response = paymentService.confirmPayment("payment-key", "capture-idem-1");

        verify(paymentCommandService).createFdsRequest("payment-key");
        verify(fdsClient).check(any());
        verify(paymentCommandService).failFds(1L, 20L, fdsResponse);
        verify(cardClient, never()).capture(any(), any());
        assertThat(response).isEqualTo(expectedResponse);
    }

    @Test
    void capturesWhenFdsSucceeds() {
        FdsRequestContext fdsContext = new FdsRequestContext(
                1L,
                20L,
                "payment-key",
                "order-12345",
                "merchant-1",
                50_000L
        );
        CaptureRequestContext captureContext = new CaptureRequestContext(
                1L,
                30L,
                "auth-123",
                "payment-key",
                "order-12345",
                50_000L
        );
        FdsCheckResponse fdsResponse = new FdsCheckResponse(true, "APPROVE", "fds-123");
        CardCaptureResponse captureResponse = new CardCaptureResponse(true, "capture-123");
        PaymentResponse expectedResponse = new PaymentResponse(
                "payment-key",
                "order-12345",
                PaymentIntentStatus.DONE,
                50_000L,
                Instant.parse("2026-04-23T10:15:30.123Z")
        );

        when(paymentCommandService.createFdsRequest("payment-key")).thenReturn(fdsContext);
        when(fdsClient.check(any())).thenReturn(fdsResponse);
        when(paymentCommandService.completeFdsAndCreateCaptureRequest(
                1L,
                20L,
                fdsResponse,
                "capture-idem-1"
        )).thenReturn(captureContext);
        when(cardClient.capture(any(), any())).thenReturn(captureResponse);
        when(paymentCommandService.completeCapture(1L, 30L, captureResponse)).thenReturn(expectedResponse);

        PaymentResponse response = paymentService.confirmPayment("payment-key", "capture-idem-1");

        verify(paymentCommandService).createFdsRequest("payment-key");
        verify(fdsClient).check(any());
        verify(paymentCommandService).completeFdsAndCreateCaptureRequest(1L, 20L, fdsResponse, "capture-idem-1");
        verify(cardClient).capture(any(), any());
        verify(paymentCommandService).completeCapture(1L, 30L, captureResponse);
        assertThat(response).isEqualTo(expectedResponse);
    }
}

