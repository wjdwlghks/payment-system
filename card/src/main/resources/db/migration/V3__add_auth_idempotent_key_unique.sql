ALTER TABLE card_authorization
    ADD UNIQUE KEY uk_card_authorization_auth_idempotent_key (auth_idempotent_key);
