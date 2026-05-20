CREATE TABLE clearing_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_code VARCHAR(32) NOT NULL,
    window_start DATETIME(6) NOT NULL,
    window_end DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount BIGINT NOT NULL,
    item_count INT NOT NULL,
    cleared_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clearing_batch_batch_code (batch_code),
    KEY idx_clearing_batch_status_window_end (status, window_end),
    CONSTRAINT chk_clearing_batch_window CHECK (window_start < window_end),
    CONSTRAINT chk_clearing_batch_total_amount CHECK (total_amount >= 0),
    CONSTRAINT chk_clearing_batch_item_count CHECK (item_count >= 0)
);

CREATE TABLE clearing_batch_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    tx_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clearing_batch_item_batch_tx (batch_id, tx_id),
    UNIQUE KEY uk_clearing_batch_item_tx_id (tx_id),
    CONSTRAINT chk_clearing_batch_item_amount CHECK (amount > 0),
    CONSTRAINT fk_clearing_batch_item_batch
        FOREIGN KEY (batch_id) REFERENCES clearing_batch (id),
    CONSTRAINT fk_clearing_batch_item_transaction
        FOREIGN KEY (tx_id) REFERENCES `transaction` (id)
);
