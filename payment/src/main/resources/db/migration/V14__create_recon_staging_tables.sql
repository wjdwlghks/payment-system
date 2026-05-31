CREATE TABLE recon_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_company VARCHAR(50) NOT NULL,
    business_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    row_count INT NOT NULL,
    file_total_amount BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recon_batch_business_date (business_date),
    KEY idx_recon_batch_status (status)
);

CREATE TABLE staging_settlement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recon_batch_id BIGINT NOT NULL,
    approval_no VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    transacted_at DATETIME(6) NOT NULL,
    tx_type VARCHAR(16) NOT NULL,
    tx_status VARCHAR(16) NOT NULL,
    original_approval_no VARCHAR(100) NULL,
    match_status VARCHAR(16) NOT NULL,
    matched_tx_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staging_batch_approval (recon_batch_id, approval_no),
    KEY idx_staging_approval_no (approval_no),
    KEY idx_staging_batch_match (recon_batch_id, match_status),
    CONSTRAINT chk_staging_amount CHECK (amount > 0),
    CONSTRAINT fk_staging_recon_batch FOREIGN KEY (recon_batch_id) REFERENCES recon_batch (id),
    CONSTRAINT fk_staging_matched_tx FOREIGN KEY (matched_tx_id) REFERENCES `transaction` (id)
);
