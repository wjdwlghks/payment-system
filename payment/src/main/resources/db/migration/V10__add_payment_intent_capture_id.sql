ALTER TABLE payment_intent
    ADD COLUMN capture_id VARCHAR(100) NULL AFTER authorized_at;
