-- 승인취소(void) — 매입 전 승인건을 되돌린 기록.
-- card_capture와 상호배타적이다: 한 approval_id에 대해 둘 중 하나만 존재할 수 있으며,
-- 그 판정은 CancelService / CaptureService가 서로의 존재를 확인해 수행한다.
--
-- approval_id에 UNIQUE를 거는 이유는 card_capture와 다르다. 매입은 PG의 멱등키가 재요청을
-- 막아주지만, 취소는 PG가 응답 유실 시 같은 요청을 다시 보내도록 설계돼 있어
-- 카드사가 직접 중복을 막아야 한다(두 번째 요청은 첫 취소를 그대로 재생한다).

CREATE TABLE card_cancel (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_id        VARCHAR(100) NOT NULL,
    approval_id      VARCHAR(100) NOT NULL,
    card_request_ref VARCHAR(100) NOT NULL,
    amount           BIGINT       NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    canceled_at      TIMESTAMP(6) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_cancel_cancel_id (cancel_id),
    UNIQUE KEY uk_card_cancel_approval_id (approval_id),
    UNIQUE KEY uk_card_cancel_card_request_ref (card_request_ref)
);
