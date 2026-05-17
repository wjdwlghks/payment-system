CREATE TABLE webhook_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_outbox_event_id (event_id),
    KEY idx_webhook_outbox_publish (status, next_attempt_at, id),
    KEY idx_webhook_outbox_aggregate_id (aggregate_id)
);
