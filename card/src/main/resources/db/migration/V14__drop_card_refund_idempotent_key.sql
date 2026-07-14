-- 환불도 dedup 없이 무조건 처리한다: refund_idempotent_key 제거,
-- inquiry는 card_request_ref(이미 UNIQUE)로 조회한다.
ALTER TABLE card_refund
    DROP INDEX uk_card_refund_idempotent_key,
    DROP COLUMN refund_idempotent_key;
