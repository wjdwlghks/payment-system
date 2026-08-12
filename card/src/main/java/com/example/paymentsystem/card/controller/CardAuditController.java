package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardAuthStatus;
import com.example.paymentsystem.card.domain.CardApprovalStatus;
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

    @GetMapping("/auth-keys")
    public List<String> authKeys() {
        return authenticationRepository.findAuthCardRequestRefsByStatus(CardAuthStatus.SUCCESS);
    }

    // Stage 2 한시: 아직 매입이 없어 승인 데이터를 돌려준다.
    // 엔드포인트 이름은 검증 스크립트 호환을 위해 유지 (Stage 3에서 실제 매입으로 교체).
    @GetMapping("/capture-keys")
    public List<String> captureKeys() {
        return authenticationRepository.findApprovalCardRequestRefsByStatus(CardApprovalStatus.SUCCESS);
    }
}
