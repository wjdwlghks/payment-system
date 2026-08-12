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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileCaptureApproved(Long txId, String externalId) {
        paymentCommandService.completeCapture(txId, externalId, LedgerSourceType.RECON_ADJUSTMENT);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileCaptureDeclined(Long txId, String externalId) {
        paymentCommandService.failCapture(txId, externalId);
    }
}
