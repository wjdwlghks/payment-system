-- Stage 1: 환불/취소 제거.
-- 결제 3단계(인증 -> 승인 -> 매입) 재구성 이후 처음부터 다시 구현한다.

DROP TABLE IF EXISTS refund_risk_flag;
DROP TABLE IF EXISTS refund;

ALTER TABLE payment_intent
    DROP COLUMN refunded_amount,
    DROP COLUMN total_fee_refunded;
