-- 글로벌 핫 계정의 샤드 행. **측정 전용**이다.
--
-- 매입 한 건은 CARD_NETWORK_RECEIVABLE과 FEE_REVENUE를 반드시 건드리는데, 이 둘은 전 결제가
-- 공유하는 단 한 행이다. 기표와 같은 트랜잭션에서 잔액을 UPDATE하던 시절에는 그 한 행의
-- X-lock 때문에 매입이 그 지점에서 직렬화됐다. 샤딩은 그 행을 16개로 쪼개 경합을 나눈 대응이었고,
-- 지금 방식(append-only + 스냅샷 갱신)은 핫패스에서 계정 행을 아예 안 건드려 잠글 것을 없앴다.
--
-- 세 방식을 같은 코드베이스에서 비교하려고 샤드 행을 미리 만들어 둔다.
-- payment.ledger.balance-mode=SHARDED 일 때만 쓰이고, 나머지 모드에서는 잔액 0으로 놀고 있다.
--
-- 버킷 수는 16 고정 — payment.ledger.shard-count 기본값과 맞춰야 한다. 설정만 올리면
-- 없는 샤드를 찾다가 LedgerService가 즉시 터진다(조용히 한쪽으로 쏠리는 것보다 낫다).
INSERT INTO account (account_type, account_class, merchant_id, balance, created_at, updated_at, version)
SELECT t.account_type, t.account_class, CONCAT('GLOBAL#', LPAD(n.bucket, 2, '0')), 0, NOW(6), NOW(6), 0
  FROM (
        SELECT 'CARD_NETWORK_RECEIVABLE' AS account_type, 'ASSET'   AS account_class
  UNION SELECT 'FEE_REVENUE',                             'REVENUE'
  ) t
  CROSS JOIN (
        SELECT 0 AS bucket UNION SELECT 1  UNION SELECT 2  UNION SELECT 3
  UNION SELECT 4          UNION SELECT 5  UNION SELECT 6  UNION SELECT 7
  UNION SELECT 8          UNION SELECT 9  UNION SELECT 10 UNION SELECT 11
  UNION SELECT 12         UNION SELECT 13 UNION SELECT 14 UNION SELECT 15
  ) n;
