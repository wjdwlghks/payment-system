-- 카드사는 멱등을 보장하지 않는다: dedup용 멱등키/해시를 제거하고
-- inquiry 매칭 키인 card_request_ref로 단일화한다.
ALTER TABLE card_authorization
    DROP INDEX uk_card_authorization_auth_idempotent_key,
    DROP INDEX uk_card_authorization_capture_idempotent_key,
    DROP COLUMN auth_idempotent_key,
    DROP COLUMN auth_hash,
    DROP COLUMN capture_idempotent_key,
    DROP COLUMN capture_hash,
    DROP COLUMN version,
    ADD COLUMN card_request_ref VARCHAR(100) NOT NULL,
    ADD UNIQUE KEY uk_card_authorization_card_request_ref (card_request_ref),
    ADD UNIQUE KEY uk_card_authorization_capture_card_request_ref (capture_card_request_ref);
