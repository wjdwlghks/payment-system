-- 카드/FDS로 보내는 correlation 키를 cardRequestRef로 단일화.
-- 트랜잭션의 idempotent_key는 더 이상 외부 호출/inquiry에 사용되지 않으므로 제거한다.
ALTER TABLE `transaction`
    DROP COLUMN idempotent_key;
