ALTER TABLE card_authorization
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
