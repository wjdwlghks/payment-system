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
        return txRepository.findCardRequestRefsByTypeAndStatus(
                TransactionType.AUTH, TransactionStatus.SUCCEEDED);
    }

    // Stage 2 한시: 아직 매입이 없어 승인(APPROVE)을 대상으로 삼는다.
    // Stage 3에서 매입(CAPTURE)이 생기면 그쪽으로 옮긴다.
    @GetMapping("/capture-keys")
    public List<String> captureKeys() {
        return txRepository.findCardRequestRefsByTypeAndStatus(
                TransactionType.APPROVE, TransactionStatus.SUCCEEDED);
    }
}
