ALTER TABLE `transaction`
    ADD COLUMN external_id VARCHAR(100) NULL AFTER idempotent_key;

UPDATE payment_intent
SET status = 'AUTH_FAILED'
WHERE status = 'AUTH_FAIL';
