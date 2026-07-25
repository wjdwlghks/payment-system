-- 카드사 매입 수수료 계정 (EXPENSE) 신설 — CLEARING 단계에서 카드사 몫을 확정할 때 사용
INSERT INTO account (
    account_type,
    account_class,
    merchant_id,
    balance,
    created_at,
    updated_at
) VALUES
    ('CARD_NETWORK_FEE', 'EXPENSE', 'GLOBAL', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

-- settlement_run_item: 정산 대상이 TX 단위(캡처 건)가 아니라 카드사 확정 배치(ClearingBatch) 단위로 바뀜
ALTER TABLE settlement_run_item
    DROP FOREIGN KEY fk_settlement_run_item_capture_tx,
    DROP KEY uk_settlement_run_item_capture_tx_id,
    DROP KEY idx_settlement_run_item_merchant_id,
    DROP COLUMN capture_tx_id,
    DROP COLUMN merchant_id,
    ADD COLUMN clearing_batch_id BIGINT NOT NULL AFTER settlement_run_id,
    ADD CONSTRAINT uk_settlement_run_item_clearing_batch_id UNIQUE (clearing_batch_id),
    ADD CONSTRAINT fk_settlement_run_item_clearing_batch
        FOREIGN KEY (clearing_batch_id) REFERENCES clearing_batch (id);

-- refund_risk_flag: MERCHANT_PENDING/MERCHANT_AVAILABLE 통합으로 available_balance 축이 사라짐
ALTER TABLE refund_risk_flag
    DROP COLUMN available_balance;
