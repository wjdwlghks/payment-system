ALTER TABLE card_authorization
    ADD UNIQUE KEY uk_card_authorization_capture_idempotent_key (capture_idempotent_key);
