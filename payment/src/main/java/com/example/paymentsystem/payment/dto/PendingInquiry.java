package com.example.paymentsystem.payment.dto;

import java.time.Instant;

/** 재기동 시 큐를 복원하기 위한 최소 정보 — id와 남은 지연을 계산할 due 시각. */
public record PendingInquiry(Long transactionId, Instant nextInquiryAt) {
}
