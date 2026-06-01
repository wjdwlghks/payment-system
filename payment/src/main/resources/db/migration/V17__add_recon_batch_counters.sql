ALTER TABLE recon_batch
    ADD COLUMN ingestion_failed_count INT NOT NULL DEFAULT 0,
    ADD COLUMN discrepancy_count INT NOT NULL DEFAULT 0,
    ADD COLUMN auto_resolved_count INT NOT NULL DEFAULT 0,
    ADD COLUMN clearing_amount BIGINT NULL,
    ADD COLUMN abort_reason VARCHAR(30) NULL,
    ADD COLUMN abort_message TEXT NULL;
