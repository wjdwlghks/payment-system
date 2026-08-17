#!/usr/bin/env bash
# run-ledger-contention.sh — 매입 기표의 락 대기 병목을 세 방식으로 비교한다.
#
#   INLINE    기표와 같은 트랜잭션에서 account.balance를 UPDATE — 원래 방식.
#             CARD_NETWORK_RECEIVABLE / FEE_REVENUE는 전 결제가 공유하는 단 한 행이라
#             그 행의 X-lock에 매입이 직렬화된다.
#   SHARDED   같은 인라인이되 그 두 계정을 16버킷으로 쪼갠다 — 경합을 나눈 대응.
#   SNAPSHOT  원장 entry만 INSERT하고 잔액은 스케줄러가 갱신 — 핫패스에서 계정 행을
#             아예 안 건드려 잠글 것이 없다. 현재 운영 방식.
#
# 지표 네 개:
#   throughput   완주 결제/초 (분모는 부하 구간)
#   p95          매입 응답 p95
#   lock_waits   Innodb_row_lock_waits 델타 — 락을 기다린 횟수
#   total_wait   Innodb_row_lock_time 델타(ms) — 기다린 시간의 총합
#
# 신호를 흐리는 것은 전부 껐다: 장애 주입 없음, 카드사 한 곳, Bulkhead/서킷 사실상 해제.
# 카드사 한 곳에 전부 몰리므로 퍼밋 80이 DB보다 먼저 걸린다 — 그러면 재는 게 달라진다.
#
# 사용법: ./run-ledger-contention.sh [duration] [tps...]
#   예) ./run-ledger-contention.sh 1m 100 200 400
#       MODES="INLINE SNAPSHOT" ./run-ledger-contention.sh 1m 200

set -euo pipefail

DURATION=${1:-1m}
shift || true
TPS_LIST=${*:-100 200 400}

MODES=${MODES:-"INLINE SHARDED SNAPSHOT"}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
FDS_URL="http://localhost:8083"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/../results"
K6_SCRIPT="$SCRIPT_DIR/../k6/ledger_contention.js"

mkdir -p "$RESULTS_DIR"
STAMP=$(date +%Y%m%d_%H%M%S)
RESULT_FILE="$RESULTS_DIR/${STAMP}_ledger_contention_d${DURATION}.json"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
: > "$WORK/rows.ndjson"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# Bulkhead/서킷을 사실상 무한대로. 카드사가 한 곳이라 기본 퍼밋(80)이 DB보다 먼저 걸린다.
# 커넥션 풀은 퍼밋보다 위여야 한다는 불변식이 있으므로 같이 올린다.
export CARD_BULKHEAD_MAX=1000000
export CARD_MAX_CONN_PER_ROUTE=512
export CARD_MAX_CONN_TOTAL=1024
# 서킷도 끈다. 매입이 DB에서 막혀 JVM 전체가 늘어지면 카드사 호출이 느려 보여
# slow-call 비율로 브레이커가 열릴 수 있고, 그러면 DB가 아니라 브레이커를 재게 된다.
export CARD_CB_MIN_CALLS=100000000

mysql_status() {  # Innodb_row_lock_* 를 JSON으로
  docker exec payment-mysql mysql -uroot -proot -sN \
    -e "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'" 2>/dev/null \
  | python3 -c "
import sys, json
print(json.dumps({k: int(v) for k, v in (l.split('\t') for l in sys.stdin.read().splitlines() if l)}))"
}

# 잔액이 원장과 맞는지. 인라인 모드가 락을 잘못 잡아 갱신을 잃으면 여기서 드러난다.
# 부호 규칙은 Account.computeNetDelta와 같다 (ASSET/EXPENSE는 차변 +, LIABILITY/REVENUE는 대변 +).
balance_drift() {
  docker exec payment-mysql mysql -upayment -ppayment -D payment -sN -e "
    SELECT COUNT(*) FROM (
      SELECT a.id
        FROM account a
        LEFT JOIN ledger_entry e ON e.account_id = a.id AND e.applied = 1
       GROUP BY a.id, a.balance, a.account_class
      HAVING a.balance <> COALESCE(SUM(
               CASE WHEN a.account_class IN ('ASSET','EXPENSE')
                    THEN CASE WHEN e.direction='DEBIT'  THEN e.amount ELSE -e.amount END
                    ELSE CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END
               END), 0)
    ) drift;" 2>/dev/null
}

for MODE in $MODES; do
  log "############ mode=$MODE ############"

  # 모드는 기동 시 설정으로 들어가므로 모드마다 새로 띄운다. 볼륨도 지워 계정 잔액을 0에서 시작한다.
  export LEDGER_BALANCE_MODE="$MODE"
  docker compose down -v >/dev/null 2>&1
  docker compose up -d >/dev/null 2>&1

  log "Waiting for services..."
  for url in "$PAYMENT_URL" "$CARD_A_URL" "$FDS_URL"; do
    until curl -sf "$url/admin/failure" >/dev/null 2>&1; do sleep 3; done
  done
  # 이 측정에는 장애가 없어야 한다. 이전 실행이 남긴 룰이 있으면 조용히 결과를 오염시킨다.
  for url in "$PAYMENT_URL" "$CARD_A_URL" "$FDS_URL"; do
    curl -sf -X DELETE "$url/admin/failure" >/dev/null
  done
  log "Ready. mode=$MODE bulkhead=$CARD_BULKHEAD_MAX pool=$CARD_MAX_CONN_PER_ROUTE/route"

  for TPS in $TPS_LIST; do
    log "--- $MODE @ ${TPS} TPS for $DURATION ---"

    LOG_SINCE=$(date -u +%Y-%m-%dT%H:%M:%S)
    mysql_status > "$WORK/lock_before.json"

    k6 run -q \
      -e PAYMENT="$PAYMENT_URL" -e TPS="$TPS" -e DURATION="$DURATION" \
      -e LEDGER_BALANCE_MODE="$MODE" -e CARD_BULKHEAD_MAX="$CARD_BULKHEAD_MAX" \
      -e SUMMARY_OUT="$WORK/k6.json" \
      "$K6_SCRIPT" >/dev/null

    mysql_status > "$WORK/lock_after.json"

    # 데드락과 락 대기 타임아웃은 카운터가 없다. 애플리케이션 로그로 센다.
    DB_ERRORS=$(docker logs payment --since "$LOG_SINCE" 2>&1 \
      | grep -c "Deadlock found\|Lock wait timeout exceeded" || true)
    DRIFT=$(balance_drift)

    python3 - "$WORK" "$MODE" "$TPS" "$DB_ERRORS" "$DRIFT" <<'EOF' >> "$WORK/rows.ndjson"
import json, sys
work, mode, tps, db_errors, drift = sys.argv[1:6]
k6      = json.load(open(f"{work}/k6.json"))
before  = json.load(open(f"{work}/lock_before.json"))
after   = json.load(open(f"{work}/lock_after.json"))

waits      = after["Innodb_row_lock_waits"] - before["Innodb_row_lock_waits"]
total_wait = after["Innodb_row_lock_time"]  - before["Innodb_row_lock_time"]

print(json.dumps({
    "mode": mode,
    "tps": int(tps),
    "throughput":   k6["headline"]["throughput"],
    "captureP95Ms": k6["headline"]["captureP95Ms"],
    "lockWaits":    waits,
    "totalWaitMs":  total_wait,
    # 기다린 한 건당 평균. 누적 평균인 Innodb_row_lock_time_avg 대신 이 구간의 델타로 낸다.
    "avgWaitMs":    round(total_wait / waits, 1) if waits else 0,
    "completedPct": k6["headline"]["completedPct"],
    "latency":      k6["latency"],
    "throughputDetail": k6["throughput"],
    "validity":     k6["validity"],
    # 잔액이 원장과 어긋난 계정 수. 인라인이 갱신을 잃으면 0이 아니게 된다.
    "balanceDrift": int(drift),
    # 데드락 / 락 대기 타임아웃 로그 건수.
    "dbLockErrors": int(db_errors),
}))
EOF
    tail -1 "$WORK/rows.ndjson" | python3 -c "
import json, sys
r = json.load(sys.stdin)
print(f\"    throughput {r['throughput']}/s · p95 {r['captureP95Ms']}ms · \"
      f\"lock_waits {r['lockWaits']:,} · total_wait {r['totalWaitMs']:,}ms\")"
  done
done

log "=== 비교 ==="
python3 - "$WORK/rows.ndjson" "$RESULT_FILE" "$DURATION" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
result_file, duration = sys.argv[2], sys.argv[3]

json.dump({"duration": duration, "runs": rows}, open(result_file, "w"), indent=2)

n = lambda v: f"{v:,}"
modes = list(dict.fromkeys(r["mode"] for r in rows))
tps_points = sorted({r["tps"] for r in rows})
find = lambda mode, tps: next((r for r in rows if r["mode"] == mode and r["tps"] == tps), None)

for title, key, fmt in [
    ("처리량 (완주 결제/초)", "throughput",   lambda v: f"{v}"),
    ("매입 p95 (ms)",        "captureP95Ms", lambda v: n(v)),
    ("lock_waits (건)",      "lockWaits",    lambda v: n(v)),
    ("total_wait (ms)",      "totalWaitMs",  lambda v: n(v)),
]:
    print(f"\n  {title}")
    print(f"    {'TPS':>6} " + "".join(f"{m:>12}" for m in modes))
    for tps in tps_points:
        cells = ""
        for mode in modes:
            r = find(mode, tps)
            cells += f"{fmt(r[key]) if r else '-':>12}"
        print(f"    {tps:>6} " + cells)

print("\n  건전성 (0이 아니면 그 런은 해석하기 전에 원인부터 봐야 한다)")
print(f"    {'모드':<10}{'TPS':>6}{'잔액 어긋남':>12}{'DB 락 에러':>11}{'drop':>8}{'httpError':>11}")
for r in rows:
    v = r["validity"]
    print(f"    {r['mode']:<10}{r['tps']:>6}{r['balanceDrift']:>12}{r['dbLockErrors']:>11}"
          f"{v['droppedIterations']:>8}{v['httpError']:>11}")
EOF

log ""
log "Saved to $RESULT_FILE"
