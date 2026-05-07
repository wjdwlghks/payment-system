ALTER TABLE card_authorization
    ADD COLUMN capture_hash CHAR(64) NULL AFTER capture_id;
