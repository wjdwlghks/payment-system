ALTER TABLE card_authorization
    ADD COLUMN capture_id VARCHAR(100) NULL AFTER capture_idempotent_key,
    ADD UNIQUE KEY uk_card_authorization_capture_id (capture_id);
