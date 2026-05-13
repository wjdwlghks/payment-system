ALTER TABLE card_authorization
    ADD COLUMN auth_status VARCHAR(30) NOT NULL DEFAULT 'SUCCESS' AFTER amount,
    ADD COLUMN capture_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED' AFTER auth_status;

UPDATE card_authorization
SET auth_status = CASE
        WHEN status IN ('AUTHORIZED', 'CAPTURED') THEN 'SUCCESS'
        ELSE 'FAILED'
    END,
    capture_status = CASE
        WHEN status = 'CAPTURED' THEN 'SUCCESS'
        ELSE 'NOT_STARTED'
    END;
