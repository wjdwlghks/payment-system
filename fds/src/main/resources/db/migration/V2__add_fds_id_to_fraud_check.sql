ALTER TABLE fraud_check
    ADD COLUMN fds_id VARCHAR(100) NOT NULL AFTER idempotency_key,
    ADD UNIQUE KEY uk_fraud_check_fds_id (fds_id);
