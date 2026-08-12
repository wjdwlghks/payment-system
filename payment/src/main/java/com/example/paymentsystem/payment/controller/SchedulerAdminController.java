package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.component.FdsScheduler;
import com.example.paymentsystem.payment.component.InquiryScheduler;
import com.example.paymentsystem.payment.component.WebhookScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/scheduler")
@RequiredArgsConstructor
public class SchedulerAdminController {

    private final InquiryScheduler inquiryScheduler;
    private final FdsScheduler fdsScheduler;
    private final WebhookScheduler webhookScheduler;

    @PostMapping("/run-now")
    public void runNow() {
        inquiryScheduler.inquiryUnknownPayment();
        inquiryScheduler.inquiryStaleRequested();
        inquiryScheduler.recoverStaleIdempotencyKeys();
        fdsScheduler.checkAuthenticatedPayment();
        webhookScheduler.webhook();
    }
}
