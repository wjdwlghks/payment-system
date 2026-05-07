ALTER TABLE fraud_check
    ADD COLUMN hash CHAR(64) NOT NULL AFTER fds_id;
