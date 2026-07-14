-- FDS도 멱등을 보장하지 않는다: dedup용 idempotency_key/hash를 제거하고
-- inquiry 매칭 키인 request_ref로 단일화한다.
ALTER TABLE fraud_check
    DROP INDEX uk_fraud_check_idempotency_key,
    DROP COLUMN idempotency_key,
    DROP COLUMN hash,
    ADD COLUMN request_ref VARCHAR(100) NOT NULL,
    ADD UNIQUE KEY uk_fraud_check_request_ref (request_ref);
