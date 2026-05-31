CREATE TABLE staging_failed (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recon_batch_id BIGINT NOT NULL,
    approval_no VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    transacted_at DATETIME(6) NOT NULL,
    tx_type VARCHAR(16) NOT NULL,
    tx_status VARCHAR(16) NOT NULL,
    original_approval_no VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_staging_failed_batch (recon_batch_id),
    CONSTRAINT fk_staging_failed_recon_batch FOREIGN KEY (recon_batch_id) REFERENCES recon_batch (id)
);
