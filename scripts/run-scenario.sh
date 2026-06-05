#!/usr/bin/env bash
# run-scenario.sh  <failure_location> <failure_type> <trigger_probability> [vus] [duration]
#
# failure_location : auth | fds | capture | all | none
# failure_type     : CONNECT_FAILURE | TIMEOUT_BEFORE_PROCESS | TIMEOUT_AFTER_PROCESS | ERROR_500
# trigger_probability : 0.0–1.0
# vus              : virtual users (default 10)
# duration         : k6 duration string (default 30s)
#
# CONNECT_FAILURE  → payment 서버 (FailureSimulationInterceptor)
#   aliases: card_auth / card_capture / fds_check
# 나머지           → card/fds 서버 (FailureFilter)
#   card aliases : auth / capture
#   fds  aliases : fds_check

set -euo pipefail

FAILURE_LOCATION=${1:-none}
FAILURE_TYPE=${2:-CONNECT_FAILURE}
TRIGGER_PROBABILITY=${3:-0.3}
VUS=${4:-10}
DURATION=${5:-30s}

PAYMENT_URL="http://localhost:8082"
MERCHANT_URL="http://localhost:8081"
CARD_URL="http://localhost:8084"
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
REMAINING=99999

mkdir -p "$RESULTS_DIR"
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_${FAILURE_LOCATION}_${FAILURE_TYPE}_p${TRIGGER_PROBABILITY}.json"

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
curl -sf -X DELETE "$FDS_URL/admin/failure"
curl -sf -X POST   "$PAYMENT_URL/admin/metrics/recovery/reset"

# ── 3. 장애 규칙 등록 ────────────────────────────────────────────────────────
log "=== 3. Register failure rules: location=$FAILURE_LOCATION type=$FAILURE_TYPE prob=$TRIGGER_PROBABILITY ==="

register_payment() {
  local ALIAS=$1
  curl -sf -X POST "$PAYMENT_URL/admin/failure" \
    -H 'Content-Type: application/json' \
    -d "{\"endpoint\":\"$ALIAS\",\"failure\":\"$FAILURE_TYPE\",\"remaining\":$REMAINING,\"triggerProbability\":$TRIGGER_PROBABILITY}"
}

register_card() {
  local ALIAS=$1
  curl -sf -X POST "$CARD_URL/admin/failure" \
    -H 'Content-Type: application/json' \
    -d "{\"endpoint\":\"$ALIAS\",\"failure\":\"$FAILURE_TYPE\",\"remaining\":$REMAINING,\"triggerProbability\":$TRIGGER_PROBABILITY}"
}

register_fds() {
  local ALIAS=$1
  curl -sf -X POST "$FDS_URL/admin/failure" \
    -H 'Content-Type: application/json' \
    -d "{\"endpoint\":\"$ALIAS\",\"failure\":\"$FAILURE_TYPE\",\"remaining\":$REMAINING,\"triggerProbability\":$TRIGGER_PROBABILITY}"
}

if [[ "$FAILURE_TYPE" == "CONNECT_FAILURE" ]]; then
  case "$FAILURE_LOCATION" in
    auth)    register_payment card_auth ;;
    fds)     register_payment fds_check ;;
    capture) register_payment card_capture ;;
    all)     register_payment card_auth; register_payment fds_check; register_payment card_capture ;;
    none)    ;;
  esac
else
  case "$FAILURE_LOCATION" in
    auth)    register_card auth ;;
    fds)     register_fds  fds_check ;;
    capture) register_card capture ;;
    all)     register_card auth; register_fds fds_check; register_card capture ;;
    none)    ;;
  esac
fi

# ── 4. k6 실행 ───────────────────────────────────────────────────────────────
log "=== 4. Run k6 (vus=$VUS duration=$DURATION) ==="
k6 run \
  -e MERCHANT_BASE="$MERCHANT_URL" \
  -e VUS="$VUS" \
  -e DURATION="$DURATION" \
  -e SUMMARY_FILE="$RESULT_FILE.k6.json" \
  "$(dirname "$0")/../k6/scenario.js"

# ── 5. 수렴 대기 (폴링 + 수동 스케줄러 트리거) ───────────────────────────────
log "=== 5. Convergence polling ==="
CONVERGE_TIMEOUT=300   # 최대 5분
CONVERGE_INTERVAL=5
elapsed=0

while true; do
  STATUS=$(curl -sf "$PAYMENT_URL/admin/convergence")
  CONVERGED=$(echo "$STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin)['converged'])")

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
log "=== 6. Generate settlement file ==="
SETTLEMENT_FILE=$(curl -sf -X POST "$CARD_URL/admin/settlements/generate")
log "Settlement file: $SETTLEMENT_FILE"

# ── 7. Ingest + Validate ─────────────────────────────────────────────────────
log "=== 7. Ingest & validate ==="
INGEST=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/ingest" \
  -H 'Content-Type: application/json' \
  -d "{\"filePath\":\"/recon-files/$SETTLEMENT_FILE\",\"cardCompany\":\"CARD_CORP\",\"businessDate\":\"$(date +%Y-%m-%d)\"}")
BATCH_ID=$(echo "$INGEST" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconBatchId'])")
log "Batch ID: $BATCH_ID"

VALIDATE=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/$BATCH_ID/validate")
log "Validate: $VALIDATE"

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
        "duration":            "$DURATION",
    },
    "recovery":    json.loads('''$RECOVERY'''),
    "verify": {
        "A_pg_internal":  json.loads('''$PG_INTERNAL'''),
        "B_auth_diff":    json.loads('''$AUTH_DIFF'''),
        "B_capture_diff": json.loads('''$CAPTURE_DIFF'''),
        "C_ledger":       json.loads('''$LEDGER'''),
        "D_idempotency":  json.loads('''$IDEMPOTENCY'''),
    },
    "passed": (
        json.loads('''$PG_INTERNAL''')["passed"]
        and json.loads('''$AUTH_DIFF''')["diff_count"] == 0
        and json.loads('''$CAPTURE_DIFF''')["diff_count"] == 0
        and json.loads('''$LEDGER''')["passed"]
        and json.loads('''$IDEMPOTENCY''')["passed"]
    ),
    "k6_summary_file": "$RESULT_FILE.k6.json",
}
with open("$RESULT_FILE", "w") as f:
    json.dump(result, f, indent=2)
print(json.dumps({"passed": result["passed"]}, indent=2))
EOF

log "Done."
