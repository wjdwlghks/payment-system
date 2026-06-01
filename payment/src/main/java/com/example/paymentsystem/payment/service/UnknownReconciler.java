package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.domain.Refund;
import com.example.paymentsystem.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UnknownReconciler {

    private final PaymentCommandService paymentCommandService;
    private final RefundCommandService refundCommandService;
    private final RefundRepository refundRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileCaptureApproved(Long txId, String externalId) {
        paymentCommandService.completeCapture(txId, externalId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileCaptureDeclined(Long txId, String externalId) {
        paymentCommandService.failCapture(txId, externalId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileRefundApproved(Long txId, String externalId) {
        Refund refund = refundRepository.findByTransactionId(txId)
                .orElseThrow(() -> new IllegalStateException(
                        "refund not found for refund transaction id=" + txId
                ));
        refundCommandService.completeRefund(txId, refund.getRefundKey(), refund.getAmount(), externalId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileRefundDeclined(Long txId, String externalId) {
        Refund refund = refundRepository.findByTransactionId(txId)
                .orElseThrow(() -> new IllegalStateException(
                        "refund not found for refund transaction id=" + txId
                ));
        refundCommandService.failRefund(txId, refund.getRefundKey(), externalId);
    }
}
