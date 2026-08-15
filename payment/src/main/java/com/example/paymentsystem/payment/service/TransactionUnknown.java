package com.example.paymentsystem.payment.service;

/**
 * 트랜잭션이 UNKNOWN으로 확정됐음을 알리는 도메인 이벤트.
 *
 * <p>커밋 후에 소비된다 — 그래야 소비자가 다른 스레드에서 읽어도 그 상태가 보인다.
 * 이 이벤트가 유실돼도(커밋 직후 크래시) {@code next_inquiry_at}이 DB에 남아 있어
 * sweeper와 재기동 복원이 이어받는다.
 */
public record TransactionUnknown(Long transactionId) {
}
