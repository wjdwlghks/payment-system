ALTER TABLE payment_intent
    ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0 AFTER amount;
