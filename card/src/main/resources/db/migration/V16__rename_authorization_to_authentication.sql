-- Stage 2: card_authorization은 실제로 인증 + 승인 두 단계를 담고 있었다.
-- 1단계를 인증(authentication), 2단계를 승인(approval)으로 재명명한다.
-- 매입(capture)은 Stage 3에서 별도 테이블로 신설된다.

RENAME TABLE card_authorization TO card_authentication;

ALTER TABLE card_authentication
    RENAME COLUMN authorized_at            TO authenticated_at,
    RENAME COLUMN capture_id               TO approval_id,
    RENAME COLUMN capture_status           TO approval_status,
    RENAME COLUMN captured_at              TO approved_at,
    RENAME COLUMN capture_card_request_ref TO approval_card_request_ref;

-- 금액은 인증이 아니라 승인의 속성이다 — 승인 시점에 채워진다.
ALTER TABLE card_authentication MODIFY COLUMN amount BIGINT NULL;
