package com.example.paymentsystem.payment.component;


import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.service.IdempotentRecoveryService;
import com.example.paymentsystem.payment.service.InquiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InquiryScheduler {

    private final InquiryService inquiryService;
    private final IdempotentRecoveryService idempotentRecoveryService;

    @Scheduled(fixedDelay = 10_000)
    public void inquiryUnknownPayment() {
        List<PaymentTransaction> unknowns = inquiryService.getUnknowns();

        for (PaymentTransaction transaction : unknowns) {
            inquire(transaction);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void inquiryStaleRequested() {
        List<PaymentTransaction> stale = inquiryService.getStaleRequested();

        for (PaymentTransaction transaction : stale) {
            inquire(transaction);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void recoverStaleIdempotencyKeys() {
        List<IdempotencyKey> stale = idempotentRecoveryService.getStaleProcessing();

        for (IdempotencyKey key : stale) {
            try {
                idempotentRecoveryService.recover(key);
            } catch (Exception e) {
                log.error("Failed to recover idempotency key. id={}", key.getId(), e);
            }
        }
    }

    private void inquire(PaymentTransaction transaction) {
        try {
            switch (transaction.getType()) {
                case AUTH -> inquiryService.inquiryAuth(transaction);
                case FDS -> inquiryService.inquiryFds(transaction);
                case CAPTURE -> inquiryService.inquiryCapture(transaction);
                case REFUND -> inquiryService.inquiryRefund(transaction);
            }
        } catch (Exception e) {
            log.error("Failed to inquire stuck transaction. transactionId={} status={}",
                    transaction.getId(), transaction.getStatus(), e);
        }
    }
}
