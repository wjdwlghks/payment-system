package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import com.example.paymentsystem.card.repository.CardCaptureRepository;
import com.example.paymentsystem.card.repository.CardAuthenticationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class CardAuditController {

    private final CardAuthenticationRepository authenticationRepository;
    private final CardCaptureRepository captureRepository;

    @GetMapping("/auth-keys")
    public List<String> authKeys() {
        return authenticationRepository.findAuthCardRequestRefsByStatus(CardAuthStatus.SUCCESS);
    }

    @GetMapping("/approve-keys")
    public List<String> approveKeys() {
        return authenticationRepository.findApprovalCardRequestRefsByStatus(CardApprovalStatus.SUCCESS);
    }

    @GetMapping("/capture-keys")
    public List<String> captureKeys() {
        return captureRepository.findCardRequestRefsByStatus(CardCaptureStatus.SUCCESS);
    }
}
