package com.example.paymentsystem.payment.service;

/**
 * 아웃박스에 웹훅이 적재됐음을 알리는 도메인 이벤트.
 *
 * <p>엔티티가 아니라 id만 싣는다 — 리스너는 커밋 <b>이후</b> 다른 스레드에서 돌기 때문에
 * 그때 새로 읽는 편이 안전하고, 그 사이 스케줄러가 먼저 보냈는지도 확인할 수 있다.
 */
public record WebhookQueued(Long outboxId) {
}
