package com.example.paymentsystem.payment.component;


import com.example.paymentsystem.payment.domain.PaymentTransaction;
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

    @Scheduled(fixedDelay = 10_000)
    public void inquiryUnknownPayment() {
        List<PaymentTransaction> unknowns = inquiryService.getUnknowns();

        for (PaymentTransaction transaction : unknowns) {
            try {
                switch (transaction.getType()) {
                    case AUTH -> inquiryService.inquiryAuth(transaction);
                    case FDS -> inquiryService.inquiryFds(transaction);
                    case CAPTURE -> inquiryService.inquiryCapture(transaction);
                    case REFUND -> inquiryService.inquiryRefund(transaction);
                }
            } catch (Exception e) {
                log.error("Failed to inquire unknown transaction. transactionId={}", transaction.getId(), e);
            }
        }
    }
}
