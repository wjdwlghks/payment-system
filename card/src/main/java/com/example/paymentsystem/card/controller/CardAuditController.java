package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardCaptureStatus;
import com.example.paymentsystem.card.repository.CardAuthorizationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class CardAuditController {

    private final CardAuthorizationRepository authorizationRepository;

    @GetMapping("/auth-keys")
    public List<String> authKeys() {
        return authorizationRepository.findAuthIdempotentKeysByStatus(CardAuthStatus.SUCCESS);
    }

    @GetMapping("/capture-keys")
    public List<String> captureKeys() {
        return authorizationRepository.findCaptureIdempotentKeysByStatus(CardCaptureStatus.SUCCESS);
    }
}
