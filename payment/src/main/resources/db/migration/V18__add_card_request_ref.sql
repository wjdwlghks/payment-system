ALTER TABLE `transaction`
    ADD COLUMN card_request_ref VARCHAR(100) NOT NULL AFTER external_id,
    ADD UNIQUE KEY uk_tx_card_request_ref (card_request_ref);

ALTER TABLE staging_settlement
    ADD COLUMN card_request_ref VARCHAR(100) NOT NULL AFTER approval_no,
    ADD UNIQUE KEY uk_staging_batch_request_ref (recon_batch_id, card_request_ref);
