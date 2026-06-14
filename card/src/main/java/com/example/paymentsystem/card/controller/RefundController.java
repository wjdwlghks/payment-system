package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardRefund;
import com.example.paymentsystem.card.domain.CardRefundStatus;
import com.example.paymentsystem.card.dto.RefundInquiryResponse;
import com.example.paymentsystem.card.dto.RefundRequest;
import com.example.paymentsystem.card.dto.RefundResponse;
import com.example.paymentsystem.card.repository.CardRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/authorizations")
@RequiredArgsConstructor
public class RefundController {

    private final CardRefundRepository cardRefundRepository;

    @PostMapping("/{captureId}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String captureId,
            @RequestBody RefundRequest request
    ) {
        Instant refundedAt = Instant.now();
        String refundId = "refund-" + UUID.randomUUID();

        CardRefund refund = new CardRefund(
                refundId,
                request.refundIdempotentKey(),
                request.cardRequestRef(),
                captureId,
                request.amount(),
                CardRefundStatus.SUCCESS,
                refundedAt
        );

        try {
            cardRefundRepository.save(refund);
        } catch (DataIntegrityViolationException e) {
            CardRefund existing = cardRefundRepository
                    .findByRefundIdempotentKey(request.refundIdempotentKey())
                    .orElseThrow(() -> new IllegalStateException("refund not found after conflict"));
            return ResponseEntity.ok(new RefundResponse(true, existing.getRefundId(), existing.getRefundedAt()));
        }

        return ResponseEntity.ok(new RefundResponse(true, refundId, refundedAt));
    }

    @GetMapping("/refund/inquiries/{refundIdempotentKey}")
    public ResponseEntity<RefundInquiryResponse> inquire(@PathVariable String refundIdempotentKey) {
        RefundInquiryResponse response = cardRefundRepository
                .findByRefundIdempotentKey(refundIdempotentKey)
                .map(r -> new RefundInquiryResponse(
                        r.getStatus() == CardRefundStatus.SUCCESS ? "success" : "failed",
                        r.getRefundId()
                ))
                .orElseGet(() -> new RefundInquiryResponse("not_found", null));
        return ResponseEntity.ok(response);
    }
}
