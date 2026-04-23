CREATE TABLE payment_intent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_key VARCHAR(64) NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    merchant_id VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    authorized_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_intent_payment_key (payment_key),
    KEY idx_payment_intent_order_id (order_id),
    KEY idx_payment_intent_merchant_id (merchant_id)
);

CREATE TABLE `transaction` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_intent_id BIGINT NOT NULL,
    type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL,
    idempotent_key VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_transaction_payment_intent_id (payment_intent_id),
    CONSTRAINT fk_transaction_payment_intent
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intent (id)
);
