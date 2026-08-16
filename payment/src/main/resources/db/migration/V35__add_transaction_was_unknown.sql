-- 조회 복구로 확정된 트랜잭션을 최종 상태만 보고는 구분할 수 없다.
-- UNKNOWN을 거쳐 SUCCEEDED가 된 행과 한 번에 SUCCEEDED가 된 행이 DB에서 같은 모습이기 때문이다.
-- 단계별 유입 경로("승인은 FDS가 바로 통과해서 온 건가, UNKNOWN이었다가 확정돼서 온 건가")를
-- 세려면 그 이력이 행에 남아야 한다.
--
-- inquiry_attempts > 0 으로도 근사할 수 있지만 그건 크래시로 REQUESTED에 방치된 건의 복구까지
-- 포함해서 "UNKNOWN이었다"와 다른 집합이 된다. 판정 기준을 근사값에 두지 않는다.
ALTER TABLE `transaction`
    ADD COLUMN was_unknown BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'UNKNOWN을 한 번이라도 거쳤는가 (되돌리지 않음)';

-- 지금 UNKNOWN인 행은 이미 그 이력을 가진 행이다.
UPDATE `transaction` SET was_unknown = TRUE WHERE status = 'UNKNOWN';

CREATE INDEX idx_transaction_funnel ON `transaction` (type, status, was_unknown);
