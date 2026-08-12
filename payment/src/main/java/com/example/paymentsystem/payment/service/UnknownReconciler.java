package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.LedgerSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UnknownReconciler {

    private final PaymentCommandService paymentCommandService;

    // Stage 2 한시: 아직 매입이 없어 승인(APPROVE)을 대상으로 삼는다.
    // Stage 3에서 매입(CAPTURE)이 생기면 그쪽으로 옮긴다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileCaptureApproved(Long txId, String externalId) {
        paymentCommandService.completeApprove(txId, externalId, LedgerSourceType.RECON_ADJUSTMENT);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileCaptureDeclined(Long txId, String externalId) {
        paymentCommandService.failApprove(txId, externalId);
    }
}
