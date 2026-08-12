-- 매입은 PG 배치가 아니라 가맹점이 결제 건별로 요청하는 API가 되었다.
-- 배치 실행 기록 테이블은 더 이상 쓰이지 않는다.
-- (transaction.capture_intent_id UNIQUE는 중복 매입 최후 방어선으로 유지한다.)

DROP TABLE IF EXISTS capture_batch_item;
DROP TABLE IF EXISTS capture_batch;
