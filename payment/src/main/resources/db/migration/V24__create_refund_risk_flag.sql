CREATE TABLE refund_risk_flag (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    merchant_id     VARCHAR(100)    NOT NULL,
    refund_tx_id    BIGINT          NOT NULL,
    pending_balance BIGINT          NOT NULL,
    available_balance BIGINT        NOT NULL,
    refund_amount   BIGINT          NOT NULL,
    net_position    BIGINT          NOT NULL,
    created_at      TIMESTAMP(6)    NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_refund_risk_flag_merchant (merchant_id),
    INDEX idx_refund_risk_flag_tx (refund_tx_id)
);
