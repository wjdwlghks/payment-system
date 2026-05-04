CREATE TABLE idempotency_keys (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation VARCHAR(30) NOT NULL,
    idempotent_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_code INT NULL,
    response_body TEXT NULL,
    expire_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_keys_operation_key (operation, idempotent_key),
    KEY idx_idempotency_keys_expire_at (expire_at)
);
