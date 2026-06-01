CREATE TABLE reconciliation_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recon_batch_id BIGINT NOT NULL,
    case_type VARCHAR(30) NOT NULL,
    staging_settlement_id BIGINT NULL,
    transaction_id BIGINT NULL,
    expected_value VARCHAR(255) NULL,
    actual_value VARCHAR(255) NULL,
    notes VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recon_result_batch_case (recon_batch_id, case_type),
    CONSTRAINT fk_recon_result_batch FOREIGN KEY (recon_batch_id) REFERENCES recon_batch (id)
);
