ALTER TABLE refund
    ADD COLUMN transaction_id BIGINT NOT NULL AFTER payment_intent_id,
    ADD CONSTRAINT fk_refund_transaction FOREIGN KEY (transaction_id) REFERENCES `transaction` (id);