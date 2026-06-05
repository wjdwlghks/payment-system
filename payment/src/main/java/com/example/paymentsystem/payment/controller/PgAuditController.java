package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class PgAuditController {

    private final PaymentTransactionRepository txRepository;

    @GetMapping("/auth-keys")
    public List<String> authKeys() {
        return txRepository.findIdempotentKeysByTypeAndStatus(
                TransactionType.AUTH, TransactionStatus.SUCCEEDED);
    }

    @GetMapping("/capture-keys")
    public List<String> captureKeys() {
        return txRepository.findIdempotentKeysByTypeAndStatus(
                TransactionType.CAPTURE, TransactionStatus.SUCCEEDED);
    }
}
