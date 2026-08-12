-- Stage 3: 매입(capture)을 별도 단계로 신설한다.
-- 승인(card_authentication.approval_id)에 대한 대금 청구 기록.

CREATE TABLE card_capture (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    capture_id       VARCHAR(100) NOT NULL,
    approval_id      VARCHAR(100) NOT NULL,
    card_request_ref VARCHAR(100) NOT NULL,
    amount           BIGINT       NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    captured_at      TIMESTAMP(6) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_capture_capture_id (capture_id),
    UNIQUE KEY uk_card_capture_card_request_ref (card_request_ref),
    KEY idx_card_capture_approval_id (approval_id)
);
