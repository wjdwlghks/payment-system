package com.example.paymentsystem.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.paymentsystem.payment.client.CardAuthResponse;
import com.example.paymentsystem.payment.client.CardCaptureResponse;
import com.example.paymentsystem.payment.client.fds.FdsCheckResponse;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentIntentStatus;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.PaymentRequest;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentCommandServiceTest {

    private final PaymentIntentRepository paymentIntentRepository = org.mockito.Mockito.mock(PaymentIntentRepository.class);
    private final PaymentTransactionRepository paymentTransactionRepository = org.mockito.Mockito.mock(PaymentTransactionRepository.class);
    private final PaymentCommandService paymentCommandService = new PaymentCommandService(
            paymentIntentRepository,
            paymentTransactionRepository
    );

    @Test
    void createsAuthRequestInSingleCommandTransaction() {
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(invocation -> {
            PaymentIntent paymentIntent = invocation.getArgument(0);
            ReflectionTestUtils.setField(paymentIntent, "id", 1L);
            return paymentIntent;
        });
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(invocation -> {
            PaymentTransaction transaction = invocation.getArgument(0);
            ReflectionTestUtils.setField(transaction, "id", 10L);
            return transaction;
        });

        AuthRequestContext context = paymentCommandService.createAuthRequest(
                new PaymentRequest("order-12345", "merchant-1", 50_000L),
                "idem-1"
        );

        assertThat(context.paymentIntentId()).isEqualTo(1L);
        assertThat(context.transactionId()).isEqualTo(10L);
        assertThat(context.paymentKey()).isNotBlank();
        assertThat(context.orderId()).isEqualTo("order-12345");
        assertThat(context.amount()).isEqualTo(50_000L);
    }

    @Test
    void completesAuthSuccess() {
        PaymentIntent paymentIntent = new PaymentIntent("payment-key", "order-12345", "merchant-1", 50_000L);
        PaymentTransaction transaction = new PaymentTransaction(paymentIntent, TransactionType.AUTH, 50_000L, "idem-1");
        when(paymentIntentRepository.findById(1L)).thenReturn(Optional.of(paymentIntent));
        when(paymentTransactionRepository.findById(10L)).thenReturn(Optional.of(transaction));
        Instant authorizedAt = Instant.parse("2026-04-23T10:15:30.123Z");

        PaymentResponse response = paymentCommandService.completeAuth(
                1L,
                10L,
                new CardAuthResponse(true, "auth-123", authorizedAt)
        );

        assertThat(paymentIntent.getStatus()).isEqualTo(PaymentIntentStatus.AUTH_READY);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(transaction.getExternalId()).isEqualTo("auth-123");
        assertThat(response.status()).isEqualTo(PaymentIntentStatus.AUTH_READY);
    }

    @Test
    void completesAuthFailure() {
        PaymentIntent paymentIntent = new PaymentIntent("payment-key", "order-12345", "merchant-1", 50_000L);
        PaymentTransaction transaction = new PaymentTransaction(paymentIntent, TransactionType.AUTH, 50_000L, "idem-1");
        when(paymentIntentRepository.findById(1L)).thenReturn(Optional.of(paymentIntent));
        when(paymentTransactionRepository.findById(10L)).thenReturn(Optional.of(transaction));

        PaymentResponse response = paymentCommandService.completeAuth(
                1L,
                10L,
                new CardAuthResponse(false, "auth-456", null)
        );

        assertThat(paymentIntent.getStatus()).isEqualTo(PaymentIntentStatus.AUTH_FAILED);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAIL);
        assertThat(transaction.getExternalId()).isEqualTo("auth-456");
        assertThat(response.status()).isEqualTo(PaymentIntentStatus.AUTH_FAILED);
    }

    @Test
    void failsFds() {
        PaymentIntent paymentIntent = new PaymentIntent("payment-key", "order-12345", "merchant-1", 50_000L);
        PaymentTransaction transaction = new PaymentTransaction(paymentIntent, TransactionType.FDS, 50_000L, null);
        when(paymentIntentRepository.findById(1L)).thenReturn(Optional.of(paymentIntent));
        when(paymentTransactionRepository.findById(20L)).thenReturn(Optional.of(transaction));

        PaymentResponse response = paymentCommandService.failFds(
                1L,
                20L,
                new FdsCheckResponse(false, "REJECT", "fds-123")
        );

        assertThat(paymentIntent.getStatus()).isEqualTo(PaymentIntentStatus.FDS_FAILED);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAIL);
        assertThat(transaction.getExternalId()).isEqualTo("fds-123");
        assertThat(response.status()).isEqualTo(PaymentIntentStatus.FDS_FAILED);
    }

    @Test
    void completesCaptureSuccess() {
        PaymentIntent paymentIntent = new PaymentIntent("payment-key", "order-12345", "merchant-1", 50_000L);
        PaymentTransaction transaction = new PaymentTransaction(paymentIntent, TransactionType.CAPTURE, 50_000L, "idem-1");
        when(paymentIntentRepository.findById(1L)).thenReturn(Optional.of(paymentIntent));
        when(paymentTransactionRepository.findById(30L)).thenReturn(Optional.of(transaction));

        PaymentResponse response = paymentCommandService.completeCapture(
                1L,
                30L,
                new CardCaptureResponse(true, "capture-123")
        );

        assertThat(paymentIntent.getStatus()).isEqualTo(PaymentIntentStatus.DONE);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(transaction.getExternalId()).isEqualTo("capture-123");
        assertThat(response.status()).isEqualTo(PaymentIntentStatus.DONE);
    }
}
