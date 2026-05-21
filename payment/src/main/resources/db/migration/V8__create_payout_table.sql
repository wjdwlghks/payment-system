CREATE TABLE payout (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payout_code VARCHAR(32) NOT NULL,
    merchant_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    paid_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_payout_code (payout_code),
    KEY idx_payout_merchant_id_status (merchant_id, status),
    CONSTRAINT chk_payout_amount CHECK (amount > 0)
);
