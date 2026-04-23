CREATE TABLE card_authorization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    auth_id VARCHAR(100) NOT NULL,
    auth_idempotent_key VARCHAR(150) NOT NULL,
    capture_idempotency_key VARCHAR(150) NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    authorized_at TIMESTAMP(6) NOT NULL,
    captured_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_authorization_auth_id (auth_id)
);
