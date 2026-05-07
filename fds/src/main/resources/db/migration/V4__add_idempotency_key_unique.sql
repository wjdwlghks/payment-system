ALTER TABLE fraud_check
    ADD UNIQUE KEY uk_fraud_check_idempotency_key (idempotency_key);
