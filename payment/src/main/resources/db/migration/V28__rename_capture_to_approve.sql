-- Stage 2: 결제 2단계를 매입(capture)이 아니라 승인(approve)으로 재해석한다.
-- AUTH는 이름을 유지하되 의미가 승인 -> 인증으로 바뀐다.
-- 매입은 Stage 3에서 별도 단계로 신설된다.

ALTER TABLE payment_intent
    RENAME COLUMN capture_id    TO approval_id,
    RENAME COLUMN authorized_at TO authenticated_at;

UPDATE `transaction` SET type = 'APPROVE' WHERE type = 'CAPTURE';

UPDATE payment_intent SET status = 'APPROVE_REQUESTED' WHERE status = 'CAPTURE_REQUESTED';
UPDATE payment_intent SET status = 'UNKNOWN_APPROVE'   WHERE status = 'UNKNOWN_CAPTURE';
UPDATE payment_intent SET status = 'APPROVE_FAILED'    WHERE status = 'CAPTURE_FAILED';
