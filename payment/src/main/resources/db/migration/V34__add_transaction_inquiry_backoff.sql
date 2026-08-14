-- inquiry 재시도를 백오프 기반으로 바꾼다.
--
-- 기존에는 UNKNOWN 트랜잭션을 `ORDER BY updated_at ASC LIMIT 300`으로 집었는데,
-- inquiry가 실패하면 updated_at이 갱신되지 않아(onUnknown이 DB를 안 건드림) 해소 불가능한
-- 행들이 큐 맨 앞을 영구 점유했다. 카드사 하나가 멈추면 그 카드사의 UNKNOWN이 300칸을
-- 전부 차지해 건강한 카드사의 결제가 영원히 조회되지 않는다.
--
-- next_inquiry_at을 시도할 때마다 미래로 밀어 그 점유를 끊는다.

ALTER TABLE `transaction`
    ADD COLUMN next_inquiry_at  DATETIME(6) NULL COMMENT '다음 inquiry 허용 시각 (백오프)',
    ADD COLUMN inquiry_attempts INT NOT NULL DEFAULT 0 COMMENT '백오프 사다리 단계';

-- 이미 UNKNOWN인 행은 즉시 대상이 되도록 채워준다.
-- NULL로 두면 `next_inquiry_at <= NOW()`가 NULL이 되어 영원히 조회되지 않는다.
UPDATE `transaction`
   SET next_inquiry_at = updated_at
 WHERE status = 'UNKNOWN'
   AND next_inquiry_at IS NULL;

CREATE INDEX idx_transaction_inquiry_due ON `transaction` (status, next_inquiry_at);
