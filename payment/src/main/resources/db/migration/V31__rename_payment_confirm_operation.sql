-- 동기 2단계가 confirm(매입)이 아니라 approve(승인)로 재해석되면서 멱등키 operation 값도 맞춘다.

UPDATE idempotency_keys SET operation = 'PAYMENT_APPROVE' WHERE operation = 'PAYMENT_CONFIRM';
