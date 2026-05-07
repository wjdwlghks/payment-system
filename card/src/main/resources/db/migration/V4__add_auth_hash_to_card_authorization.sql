ALTER TABLE card_authorization
    ADD COLUMN auth_hash CHAR(64) NOT NULL AFTER auth_idempotent_key;
