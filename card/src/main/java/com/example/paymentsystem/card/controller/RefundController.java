package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.domain.CardRefund;
import com.example.paymentsystem.card.domain.CardRefundStatus;
import com.example.paymentsystem.card.dto.RefundInquiryResponse;
import com.example.paymentsystem.card.dto.RefundRequest;
import com.example.paymentsystem.card.dto.RefundResponse;
import com.example.paymentsystem.card.repository.CardRefundRepository;
import lombok.RequiredArgsConstructor;
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

    // 카드사는 멱등을 보장하지 않는다: 도착한 refund는 무조건 처리한다 (dedup 없음).
    @PostMapping("/{captureId}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String captureId,
            @RequestBody RefundRequest request
    ) {
        Instant refundedAt = Instant.now();
        String refundId = "refund-" + UUID.randomUUID();

        CardRefund refund = new CardRefund(
                refundId,
                request.cardRequestRef(),
                captureId,
                request.amount(),
                CardRefundStatus.SUCCESS,
                refundedAt
        );

        cardRefundRepository.save(refund);

        return ResponseEntity.ok(new RefundResponse(true, refundId, refundedAt));
    }

    @GetMapping("/refund/inquiries/{cardRequestRef}")
    public ResponseEntity<RefundInquiryResponse> inquire(@PathVariable String cardRequestRef) {
        RefundInquiryResponse response = cardRefundRepository
                .findByCardRequestRef(cardRequestRef)
                .map(r -> new RefundInquiryResponse(
                        r.getStatus() == CardRefundStatus.SUCCESS ? "success" : "failed",
                        r.getRefundId()
                ))
                .orElseGet(() -> new RefundInquiryResponse("not_found", null));
        return ResponseEntity.ok(response);
    }
}
