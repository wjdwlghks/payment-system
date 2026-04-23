CREATE TABLE fraud_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(150) NOT NULL,
    payment_key VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    decision VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);
