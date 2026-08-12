-- 성공 상태를 "다음 단계 준비됨(X_READY)"이 아니라 "도달한 지점(과거분사)"으로 명명한다.
-- 특히 DONE은 Stage 3 이후 실제로 틀린 이름이었다 — 승인까지 끝났을 뿐 매입/청산/정산/지급이 남는다.

UPDATE payment_intent SET status = 'AUTHENTICATED' WHERE status = 'AUTH_READY';
UPDATE payment_intent SET status = 'FDS_PASSED'    WHERE status = 'FDS_READY';
UPDATE payment_intent SET status = 'APPROVED'      WHERE status = 'DONE';
