-- Stage 3: 매입(capture)을 동기 경로 밖 배치로 신설한다.
-- 원장 기표(LedgerPostingType.CAPTURE)가 승인 시점에서 이 시점으로 옮겨온다.

CREATE TABLE capture_batch (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    batch_code       VARCHAR(32)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempted_count  INT          NOT NULL,
    succeeded_count  INT          NOT NULL,
    failed_count     INT          NOT NULL,
    unknown_count    INT          NOT NULL,
    succeeded_amount BIGINT       NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    completed_at     TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_capture_batch_batch_code (batch_code)
);

CREATE TABLE capture_batch_item (
    id       BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    tx_id    BIGINT NOT NULL,
    amount   BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_capture_batch_item_tx_id (tx_id),
    KEY idx_capture_batch_item_batch_id (batch_id),
    CONSTRAINT fk_capture_batch_item_batch FOREIGN KEY (batch_id) REFERENCES capture_batch (id),
    CONSTRAINT fk_capture_batch_item_tx    FOREIGN KEY (tx_id)    REFERENCES `transaction` (id)
);

-- 한 결제당 매입 tx는 하나뿐이다. MySQL엔 부분 유니크 인덱스가 없어 생성 컬럼으로 만든다.
-- (엔티티에 매핑하지 않는 DB 전용 컬럼 — ddl-auto: validate는 미매핑 컬럼을 문제 삼지 않는다.)
ALTER TABLE `transaction`
    ADD COLUMN capture_intent_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN type = 'CAPTURE' THEN payment_intent_id END) STORED,
    ADD UNIQUE KEY uk_transaction_capture_intent (capture_intent_id);
