ALTER TABLE card_authorization
    CHANGE COLUMN capture_idempotency_key capture_idempotent_key VARCHAR(150) NULL;
