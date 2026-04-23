package com.example.paymentsystem.fds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.paymentsystem.fds.domain.FraudCheck;
import com.example.paymentsystem.fds.domain.FraudDecision;
import com.example.paymentsystem.fds.dto.FraudCheckRequest;
import com.example.paymentsystem.fds.dto.FraudCheckResponse;
import com.example.paymentsystem.fds.repository.FraudCheckRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FraudCheckServiceTest {

    private final FraudCheckRepository fraudCheckRepository = org.mockito.Mockito.mock(FraudCheckRepository.class);
    private final FraudCheckService fraudCheckService = new FraudCheckService(fraudCheckRepository);

    @Test
    void createsApprovedFraudCheck() {
        when(fraudCheckRepository.save(any(FraudCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FraudCheckResponse response = fraudCheckService.check(new FraudCheckRequest(
                "payment-key",
                "order-12345",
                "merchant-1",
                50_000L
        ));

        ArgumentCaptor<FraudCheck> captor = ArgumentCaptor.forClass(FraudCheck.class);
        org.mockito.Mockito.verify(fraudCheckRepository).save(captor.capture());
        FraudCheck fraudCheck = captor.getValue();

        assertThat(response.success()).isTrue();
        assertThat(response.result()).isEqualTo("APPROVE");
        assertThat(response.externalId()).startsWith("fds-");
        assertThat(fraudCheck.getIdempotencyKey()).isEqualTo("payment-key:fds");
        assertThat(fraudCheck.getPaymentKey()).isEqualTo("payment-key");
        assertThat(fraudCheck.getAmount()).isEqualTo(50_000L);
        assertThat(fraudCheck.getDecision()).isEqualTo(FraudDecision.APPROVE);
    }
}
