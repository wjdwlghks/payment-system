CREATE TABLE card_refund (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    refund_id           VARCHAR(100) NOT NULL,
    refund_idempotent_key VARCHAR(150) NOT NULL,
    card_request_ref    VARCHAR(100) NOT NULL,
    capture_id          VARCHAR(100) NOT NULL,
    amount              BIGINT NOT NULL,
    status              VARCHAR(20) NOT NULL,
    refunded_at         TIMESTAMP(6) NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_refund_refund_id (refund_id),
    UNIQUE KEY uk_card_refund_card_request_ref (card_request_ref),
    UNIQUE KEY uk_card_refund_idempotent_key (refund_idempotent_key)
);
