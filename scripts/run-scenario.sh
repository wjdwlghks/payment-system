#!/usr/bin/env bash
# run-scenario.sh  <failure_location> <failure_type> <trigger_probability> [vus] [duration] [warmup] [warmdown]
#
# failure_location : auth | fds | capture | all | none
# failure_type     : CONNECT_FAILURE | TIMEOUT_BEFORE_PROCESS | TIMEOUT_AFTER_PROCESS | ERROR_500 | all | none
# trigger_probability : 0.0–1.0  (각 장애 유형별 독립 확률)
# vus              : virtual users (default 10)
# duration         : k6 steady-state duration string (default 2m)
# warmup           : ramp-up duration (default 30s)
# warmdown         : ramp-down duration (default 30s)
#
# failure_type=all 등록 위치:
#   auth    : CONNECT_FAILURE → payment(card_auth)
#             TIMEOUT_BEFORE/AFTER, ERROR_500 → card(auth)
#   fds     : TIMEOUT_BEFORE/AFTER, ERROR_500 → fds(fds_check)  ※ CONNECT_FAILURE 제외
#   capture : CONNECT_FAILURE → payment(card_capture)
#             TIMEOUT_BEFORE/AFTER, ERROR_500 → card(capture)
#
# 단일 failure_type 등록 위치:
#   CONNECT_FAILURE          → payment 서버 (FailureSimulationInterceptor)
#   TIMEOUT_*/ERROR_500      → card/fds 서버 (FailureFilter)

set -euo pipefail

FAILURE_LOCATION=${1:-none}
FAILURE_TYPE=${2:-none}
TRIGGER_PROBABILITY=${3:-0.3}
VUS=${4:-10}
DURATION=${5:-2m}
WARMUP=${6:-30s}
WARMDOWN=${7:-30s}

PAYMENT_URL="http://localhost:8082"
MERCHANT_URL="http://localhost:8081"
CARD_URL="http://localhost:8084"    # card-a (장애 주입 대상)
CARD_B_URL="http://localhost:8085"  # card-b (항상 정상)
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
REMAINING=99999

mkdir -p "$RESULTS_DIR"
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_${FAILURE_LOCATION}_${FAILURE_TYPE}_p${TRIGGER_PROBABILITY}_v${VUS}_w${WARMUP}_d${DURATION}.json"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ── 1. DB 초기화 ─────────────────────────────────────────────────────────────
log "=== 1. DB reset ==="
docker compose down -v
docker compose up -d --build
log "Waiting for services to be ready..."
until curl -sf "$PAYMENT_URL/v1/payment" -X POST \
    -H 'Content-Type: application/json' \
    -d '{"orderId":"_health","merchantId":"_","amount":1}' >/dev/null 2>&1; do
  sleep 3
done
log "Services ready."

# ── 2. 장애 설정 해제 + 메트릭 리셋 ──────────────────────────────────────────
log "=== 2. Clear failure rules + reset metrics ==="
curl -sf -X DELETE "$PAYMENT_URL/admin/failure"
curl -sf -X DELETE "$CARD_URL/admin/failure"
curl -sf -X DELETE "$CARD_B_URL/admin/failure"
curl -sf -X DELETE "$FDS_URL/admin/failure"
curl -sf -X POST   "$PAYMENT_URL/admin/metrics/recovery/reset"

# ── 3. 장애 규칙 등록 ────────────────────────────────────────────────────────
log "=== 3. Register failure rules: location=$FAILURE_LOCATION type=$FAILURE_TYPE prob=$TRIGGER_PROBABILITY ==="

register_payment() {
  local ALIAS=$1 TYPE=$2
  curl -sf -X POST "$PAYMENT_URL/admin/failure" \
    -H 'Content-Type: application/json' \
    -d "{\"endpoint\":\"$ALIAS\",\"failure\":\"$TYPE\",\"remaining\":$REMAINING,\"triggerProbability\":$TRIGGER_PROBABILITY}"
}

register_card() {
  local ALIAS=$1 TYPE=$2
  curl -sf -X POST "$CARD_URL/admin/failure" \
    -H 'Content-Type: application/json' \
    -d "{\"endpoint\":\"$ALIAS\",\"failure\":\"$TYPE\",\"remaining\":$REMAINING,\"triggerProbability\":$TRIGGER_PROBABILITY}"
}

register_fds() {
  local ALIAS=$1 TYPE=$2
  curl -sf -X POST "$FDS_URL/admin/failure" \
    -H 'Content-Type: application/json' \
    -d "{\"endpoint\":\"$ALIAS\",\"failure\":\"$TYPE\",\"remaining\":$REMAINING,\"triggerProbability\":$TRIGGER_PROBABILITY}"
}

register_auth_all() {
  register_payment card_auth CONNECT_FAILURE
  register_card    auth      TIMEOUT_BEFORE_PROCESS
  register_card    auth      TIMEOUT_AFTER_PROCESS
  register_card    auth      ERROR_500
}

register_fds_all() {
  # CONNECT_FAILURE 제외 — inquiry 메커니즘 검증엔 불필요
  register_fds fds_check TIMEOUT_BEFORE_PROCESS
  register_fds fds_check TIMEOUT_AFTER_PROCESS
  register_fds fds_check ERROR_500
}

register_capture_all() {
  register_payment card_capture CONNECT_FAILURE
  register_card    capture      TIMEOUT_BEFORE_PROCESS
  register_card    capture      TIMEOUT_AFTER_PROCESS
  register_card    capture      ERROR_500
}

if [[ "$FAILURE_TYPE" == "all" ]]; then
  case "$FAILURE_LOCATION" in
    auth)    register_auth_all ;;
    fds)     register_fds_all ;;
    capture) register_capture_all ;;
    all)     register_auth_all; register_fds_all; register_capture_all ;;
    none)    ;;
  esac
elif [[ "$FAILURE_TYPE" == "CONNECT_FAILURE" ]]; then
  case "$FAILURE_LOCATION" in
    auth)    register_payment card_auth    CONNECT_FAILURE ;;
    fds)     register_payment fds_check   CONNECT_FAILURE ;;
    capture) register_payment card_capture CONNECT_FAILURE ;;
    all)     register_payment card_auth CONNECT_FAILURE
             register_payment fds_check CONNECT_FAILURE
             register_payment card_capture CONNECT_FAILURE ;;
    none)    ;;
  esac
elif [[ "$FAILURE_TYPE" != "none" ]]; then
  case "$FAILURE_LOCATION" in
    auth)    register_card auth      "$FAILURE_TYPE" ;;
    fds)     register_fds  fds_check "$FAILURE_TYPE" ;;
    capture) register_card capture   "$FAILURE_TYPE" ;;
    all)     register_card auth "$FAILURE_TYPE"
             register_fds  fds_check "$FAILURE_TYPE"
             register_card capture "$FAILURE_TYPE" ;;
    none)    ;;
  esac
fi

# ── 4. k6 실행 ───────────────────────────────────────────────────────────────
log "=== 4. Run k6 (vus=$VUS duration=$DURATION) ==="
k6 run \
  -e MERCHANT_BASE="$MERCHANT_URL" \
  -e VUS="$VUS" \
  -e DURATION="$DURATION" \
  -e WARMUP="$WARMUP" \
  -e WARMDOWN="$WARMDOWN" \
  -e SUMMARY_FILE="$RESULT_FILE.k6.json" \
  "$(dirname "$0")/../k6/scenario.js"

# ── 4-1. Row lock 통계 (k6 종료 후, MySQL 재시작으로 0에서 누적) ─────────────
log "=== 4-1. Row lock stats ==="
ROW_LOCK_RAW=$(docker exec payment-mysql mysql -uroot -proot -se \
  "SHOW STATUS LIKE 'Innodb_row_lock%'" 2>/dev/null)
ROW_LOCK_STATS=$(echo "$ROW_LOCK_RAW" | python3 -c "
import sys, json
d = {}
for line in sys.stdin:
    k, v = line.strip().split('\t')
    d[k] = int(v)
print(json.dumps(d))
")
log "Row lock stats: $ROW_LOCK_STATS"

# ── 4-2. k6 종료 시점 pendingWebhook 스냅샷 ──────────────────────────────────
PENDING_WEBHOOK_AT_K6_END=$(curl -sf "$PAYMENT_URL/admin/convergence" | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['pendingWebhook'])")
log "pendingWebhook at k6 end: $PENDING_WEBHOOK_AT_K6_END"

# ── 5. 수렴 대기 (폴링 + 수동 스케줄러 트리거) ───────────────────────────────
# pendingWebhook은 수렴 조건 제외 — 비동기 알림이라 일관성 검증과 무관
# 스케줄러 트리거는 계속 수행해 백그라운드로 드레인
log "=== 5. Convergence polling ==="
CONVERGE_TIMEOUT=300   # 최대 5분
CONVERGE_INTERVAL=5
elapsed=0

while true; do
  STATUS=$(curl -sf "$PAYMENT_URL/admin/convergence")
  CONVERGED=$(echo "$STATUS" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d['unknownTx'] == 0 and d['staleRequested'] == 0
      and d['authReady'] == 0 and d['processingIdempotencyKeys'] == 0)
")

  if [[ "$CONVERGED" == "True" ]]; then
    log "Converged: $STATUS"
    break
  fi

  if (( elapsed >= CONVERGE_TIMEOUT )); then
    log "WARNING: convergence timeout after ${elapsed}s — proceeding anyway. status=$STATUS"
    break
  fi

  log "Not converged yet (${elapsed}s): $STATUS — triggering schedulers..."
  curl -sf -X POST "$PAYMENT_URL/admin/scheduler/run-now" >/dev/null
  sleep "$CONVERGE_INTERVAL"
  elapsed=$(( elapsed + CONVERGE_INTERVAL ))
done

# ── 6. 정산 파일 생성 ────────────────────────────────────────────────────────
log "=== 6. Generate settlement files ==="
SETTLEMENT_FILE_A=$(curl -sf -X POST "$CARD_URL/admin/settlements/generate")
log "Settlement file A: $SETTLEMENT_FILE_A"
SETTLEMENT_FILE_B=$(curl -sf -X POST "$CARD_B_URL/admin/settlements/generate")
log "Settlement file B: $SETTLEMENT_FILE_B"

# ── 7. Ingest + Validate (card-a → clear, then card-b → clear) ───────────────
log "=== 7. Ingest & validate ==="
INGEST_A=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/ingest" \
  -H 'Content-Type: application/json' \
  -d "{\"filePath\":\"/recon-files/$SETTLEMENT_FILE_A\",\"cardCompany\":\"CARD_CORP_A\",\"businessDate\":\"$(date +%Y-%m-%d)\"}")
BATCH_A=$(echo "$INGEST_A" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconBatchId'])")
log "Batch A: $BATCH_A — $(echo "$INGEST_A" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'rows={d[\"rowCount\"]} amount={d[\"fileTotalAmount\"]}')")"

VALIDATE_A=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/$BATCH_A/validate")
log "Validate A: $VALIDATE_A"

INGEST_B=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/ingest" \
  -H 'Content-Type: application/json' \
  -d "{\"filePath\":\"/recon-files/$SETTLEMENT_FILE_B\",\"cardCompany\":\"CARD_CORP_B\",\"businessDate\":\"$(date +%Y-%m-%d)\"}")
BATCH_B=$(echo "$INGEST_B" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconBatchId'])")
log "Batch B: $BATCH_B — $(echo "$INGEST_B" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'rows={d[\"rowCount\"]} amount={d[\"fileTotalAmount\"]}')")"

VALIDATE_B=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/$BATCH_B/validate")
log "Validate B: $VALIDATE_B"

# ── 8. Recovery metrics ───────────────────────────────────────────────────────
log "=== 8. Recovery metrics ==="
RECOVERY=$(curl -sf "$PAYMENT_URL/admin/metrics/recovery")

# ── 9. 불일치 검증 ────────────────────────────────────────────────────────────
log "=== 9. Consistency verification ==="

# A. PG 내부
PG_INTERNAL=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal")
log "A. PG internal: $PG_INTERNAL"

# B. PG ↔ Card 집합 비교
PG_AUTH_KEYS=$(curl -sf "$PAYMENT_URL/admin/audit/auth-keys")
CARD_AUTH_KEYS=$(curl -sf "$CARD_URL/admin/audit/auth-keys")
PG_CAPTURE_KEYS=$(curl -sf "$PAYMENT_URL/admin/audit/capture-keys")
CARD_CAPTURE_KEYS=$(curl -sf "$CARD_URL/admin/audit/capture-keys")

AUTH_DIFF=$(python3 - <<EOF
import json
pg   = set(json.loads('''$PG_AUTH_KEYS'''))
card = set(json.loads('''$CARD_AUTH_KEYS'''))
diff = pg.symmetric_difference(card)
print(json.dumps({"pg_only": list(pg - card), "card_only": list(card - pg), "diff_count": len(diff)}))
EOF
)

CAPTURE_DIFF=$(python3 - <<EOF
import json
pg   = set(json.loads('''$PG_CAPTURE_KEYS'''))
card = set(json.loads('''$CARD_CAPTURE_KEYS'''))
diff = pg.symmetric_difference(card)
print(json.dumps({"pg_only": list(pg - card), "card_only": list(card - pg), "diff_count": len(diff)}))
EOF
)

log "B-AUTH diff:    $AUTH_DIFF"
log "B-CAPTURE diff: $CAPTURE_DIFF"

# C. Ledger
LEDGER=$(curl -sf "$PAYMENT_URL/admin/verify/ledger")
log "C. Ledger: $LEDGER"

# D. 멱등성 (Merchant→Payment: PROCESSING 잔존 0건)
IDEMPOTENCY=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal" | python3 -c "
import sys, json
d = json.load(sys.stdin)
result = {'processingIdempotencyKeys': d['processingIdempotencyKeys'], 'passed': d['processingIdempotencyKeys'] == 0}
print(json.dumps(result))
")
log "D. Idempotency: $IDEMPOTENCY"

# ── 10. 결과 기록 ─────────────────────────────────────────────────────────────
log "=== 10. Recording results to $RESULT_FILE ==="

python3 - <<EOF
import json, sys
result = {
    "scenario": {
        "failure_location":    "$FAILURE_LOCATION",
        "failure_type":        "$FAILURE_TYPE",
        "trigger_probability": $TRIGGER_PROBABILITY,
        "vus":                 $VUS,
        "warmup":              "$WARMUP",
        "duration":            "$DURATION",
        "warmdown":            "$WARMDOWN",
    },
    "recovery":    json.loads('''$RECOVERY'''),
    "row_lock":    json.loads('''$ROW_LOCK_STATS'''),
    "pending_webhook_at_k6_end": $PENDING_WEBHOOK_AT_K6_END,
    "verify": {
        "A_pg_internal":  json.loads('''$PG_INTERNAL'''),
        "B_auth_diff":    json.loads('''$AUTH_DIFF'''),
        "B_capture_diff": json.loads('''$CAPTURE_DIFF'''),
        "C_ledger":       json.loads('''$LEDGER'''),
        "D_idempotency":  json.loads('''$IDEMPOTENCY'''),
        "E_validate_a":   json.loads('''$VALIDATE_A'''),
        "E_validate_b":   json.loads('''$VALIDATE_B'''),
    },
    "passed": (
        json.loads('''$PG_INTERNAL''')["passed"]
        and json.loads('''$AUTH_DIFF''')["diff_count"] == 0
        and json.loads('''$CAPTURE_DIFF''')["diff_count"] == 0
        and json.loads('''$LEDGER''')["passed"]
        and json.loads('''$IDEMPOTENCY''')["passed"]
        and json.loads('''$VALIDATE_A''').get("missingOnCardCount", 1) == 0
        and json.loads('''$VALIDATE_B''').get("missingOnCardCount", 1) == 0
    ),
    "k6_summary_file": "$RESULT_FILE.k6.json",
}
with open("$RESULT_FILE", "w") as f:
    json.dump(result, f, indent=2)
print(json.dumps({"passed": result["passed"]}, indent=2))
EOF

log "Done."
