-- 승인(PAYMENT_APPROVE)은 요청 본문이 없어 지문 찍을 대상이 없다.
-- 멱등키(merchantId:paymentKey) 자체가 요청을 온전히 규정하므로 request_hash를 비워 둔다.

ALTER TABLE idempotency_keys MODIFY COLUMN request_hash CHAR(64) NULL;
