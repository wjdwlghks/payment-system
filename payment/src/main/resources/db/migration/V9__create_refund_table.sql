CREATE TABLE refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_key VARCHAR(64) NOT NULL,
    payment_intent_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    external_id VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_refund_key (refund_key),
    KEY idx_refund_payment_intent_id (payment_intent_id),
    CONSTRAINT chk_refund_amount CHECK (amount > 0),
    CONSTRAINT fk_refund_payment_intent
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intent (id)
);
