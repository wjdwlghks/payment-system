CREATE TABLE settlement_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_code VARCHAR(32) NOT NULL,
    window_start DATETIME(6) NOT NULL,
    window_end DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount BIGINT NOT NULL,
    item_count INT NOT NULL,
    settled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_run_run_code (run_code),
    KEY idx_settlement_run_status_window_end (status, window_end),
    CONSTRAINT chk_settlement_run_window CHECK (window_start < window_end),
    CONSTRAINT chk_settlement_run_total_amount CHECK (total_amount >= 0),
    CONSTRAINT chk_settlement_run_item_count CHECK (item_count >= 0)
);

CREATE TABLE settlement_run_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    settlement_run_id BIGINT NOT NULL,
    capture_tx_id BIGINT NOT NULL,
    merchant_id VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_run_item_capture_tx_id (capture_tx_id),
    KEY idx_settlement_run_item_run_id (settlement_run_id),
    KEY idx_settlement_run_item_merchant_id (merchant_id),
    CONSTRAINT chk_settlement_run_item_amount CHECK (amount > 0),
    CONSTRAINT fk_settlement_run_item_run
        FOREIGN KEY (settlement_run_id) REFERENCES settlement_run (id),
    CONSTRAINT fk_settlement_run_item_capture_tx
        FOREIGN KEY (capture_tx_id) REFERENCES `transaction` (id)
);
