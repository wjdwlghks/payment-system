-- 인덱스명이 개명 전 테이블명(card_authorization)을 그대로 달고 있었다.
-- status 컬럼은 V9에서 auth_status/capture_status로 쪼개진 뒤 쓰이지 않는 잔재다.

ALTER TABLE card_authentication
    RENAME INDEX uk_card_authorization_auth_id                  TO uk_card_authentication_auth_id,
    RENAME INDEX uk_card_authorization_card_request_ref         TO uk_card_authentication_card_request_ref,
    RENAME INDEX uk_card_authorization_capture_id               TO uk_card_authentication_approval_id,
    RENAME INDEX uk_card_authorization_capture_card_request_ref TO uk_card_authentication_approval_card_request_ref;

ALTER TABLE card_authentication DROP COLUMN status;
