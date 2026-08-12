#!/usr/bin/env bash
# run-payment-chaos.sh — 부하 테스트 (인증 → 승인 → 매입)
#
# 가맹점이 승인완료를 받으면 고객에게 결제완료를 알리는 동시에 매입을 요청한다고 가정한다.
# 따라서 부하 범위는 동기 3단계이고, 그 뒤의 대사/청산/정산/지급은
# scripts/run-settlement-verify.sh 가 따로 검증한다.
#
# 4종 장애(TIMEOUT_BEFORE_PROCESS/TIMEOUT_AFTER_PROCESS/ERROR_500/CONNECT_FAILURE)를
# 인증·FDS·승인·매입 각 호출에 독립적으로 균일 확률로 주입한다(k6/payment_chaos.js).
# 재시도는 없다 — 모든 모호한 결과는 UNKNOWN → inquiry로 수렴한다.
#
# 판정 계층:
#   Layer 1  PG 내부 불변식        (/admin/verify/pg-internal)
#   Layer 2  PG↔Card exactly-once  (cardRequestRef 집합 대칭차, 인증/승인/매입 3축)
#
# 사용법: ./run-payment-chaos.sh [prob] [tps] [duration]

set -euo pipefail

PROB=${1:-0.027}
TPS=${2:-30}
DURATION=${3:-2m}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
K6_SCRIPT="$(dirname "$0")/../k6/payment_chaos.js"

mkdir -p "$RESULTS_DIR"
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_payment_chaos_p${PROB}_t${TPS}_d${DURATION}.json"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ── 1. DB 초기화 + 기동 ────────────────────────────────────────────────────
log "=== 1. DB reset & build ==="
docker compose down -v
docker compose up -d --build

log "Waiting for services to be ready..."
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  until curl -sf "$url/admin/failure" >/dev/null 2>&1; do sleep 3; done
done
log "Services ready."

# ── 2. 장애 해제 + 메트릭 리셋 ──────────────────────────────────────────────
log "=== 2. Clear failure rules + reset metrics ==="
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  curl -sf -X DELETE "$url/admin/failure" >/dev/null
done
curl -sf -X POST "$PAYMENT_URL/admin/metrics/recovery/reset" >/dev/null

# ── 3. k6 실행 (setup()이 4종×4위치 주입, teardown()이 해제) ────────────────
log "=== 3. Run k6 chaos load (tps=$TPS duration=$DURATION prob=$PROB) ==="
k6 run \
  -e PAYMENT="$PAYMENT_URL" -e CARD_A="$CARD_A_URL" -e CARD_B="$CARD_B_URL" -e FDS="$FDS_URL" \
  -e TPS="$TPS" -e DURATION="$DURATION" -e PROB="$PROB" \
  "$K6_SCRIPT"

# ── 4. 장애 해제 재확인 ────────────────────────────────────────────────────
log "=== 4. Ensure all failure rules cleared ==="
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  curl -sf -X DELETE "$url/admin/failure" >/dev/null
done

# ── 5. 수렴 게이트 ─────────────────────────────────────────────────────────
# 부하가 남긴 UNKNOWN은 부하 스크립트가 치운다. 여기서 안 치우면 뒤따르는
# 대사 검증이 미확정 거래를 대상으로 삼아 틀린 결과를 낸다.
log "=== 5. Convergence gate (drain via scheduler/run-now; not timed) ==="
GATE_MAX_ITER=60
i=0
while true; do
  UNKNOWN_REMAINING=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal" | \
    python3 -c "import sys,json; print(json.load(sys.stdin)['unknownRemaining'])")
  if [[ "$UNKNOWN_REMAINING" == "0" ]]; then
    log "Converged (unknownRemaining=0)."
    break
  fi
  i=$((i+1))
  if (( i >= GATE_MAX_ITER )); then
    log "WARNING: convergence gate exhausted after $i attempts — unknownRemaining=$UNKNOWN_REMAINING"
    break
  fi
  curl -sf -X POST "$PAYMENT_URL/admin/scheduler/run-now" >/dev/null
  sleep 5
done

RECOVERY=$(curl -sf "$PAYMENT_URL/admin/metrics/recovery")
log "Recovery (post-convergence): $RECOVERY"

# ── 6. Layer 1 — PG 내부 불변식 ────────────────────────────────────────────
log "=== 6. Layer 1 — PG internal invariants ==="
PG_INTERNAL=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal")
log "PG internal: $PG_INTERNAL"

# ── 7. Layer 2 — PG↔Card exactly-once (3축) ────────────────────────────────
log "=== 7. Layer 2 — PG <-> Card exactly-once (auth / approve / capture) ==="
diff_of() {  # $1=pg endpoint
  local pg card_a card_b
  pg=$(curl -sf "$PAYMENT_URL/admin/audit/$1")
  card_a=$(curl -sf "$CARD_A_URL/admin/audit/$1")
  card_b=$(curl -sf "$CARD_B_URL/admin/audit/$1")
  python3 - "$pg" "$card_a" "$card_b" <<'EOF'
import json, sys
pg   = set(json.loads(sys.argv[1]))
card = set(json.loads(sys.argv[2])) | set(json.loads(sys.argv[3]))
print(json.dumps({"pg_only": sorted(pg - card), "card_only": sorted(card - pg),
                  "diff_count": len(pg ^ card)}))
EOF
}
AUTH_DIFF=$(diff_of auth-keys)
APPROVE_DIFF=$(diff_of approve-keys)
CAPTURE_DIFF=$(diff_of capture-keys)
log "AUTH    diff: $AUTH_DIFF"
log "APPROVE diff: $APPROVE_DIFF"
log "CAPTURE diff: $CAPTURE_DIFF"

# ── 8. 판정 및 기록 ────────────────────────────────────────────────────────
log "=== 8. Recording load report to $RESULT_FILE ==="
python3 - <<EOF
import json

pg_internal  = json.loads('''$PG_INTERNAL''')
auth_diff    = json.loads('''$AUTH_DIFF''')
approve_diff = json.loads('''$APPROVE_DIFF''')
capture_diff = json.loads('''$CAPTURE_DIFF''')
recovery     = json.loads('''$RECOVERY''')

layer1_pass = pg_internal["passed"]
layer2_pass = (auth_diff["diff_count"] == 0
               and approve_diff["diff_count"] == 0
               and capture_diff["diff_count"] == 0)
overall = layer1_pass and layer2_pass

result = {
    "scenario": {
        "phases": ["authentication", "fds", "approve", "capture"],
        "tps": $TPS,
        "duration": "$DURATION",
        "trigger_probability_per_type": $PROB,
        "failure_types": ["TIMEOUT_BEFORE_PROCESS", "TIMEOUT_AFTER_PROCESS", "ERROR_500", "CONNECT_FAILURE"],
        "retry_model": "none (all ambiguous outcomes -> UNKNOWN -> inquiry)",
    },
    "layer1_pg_internal": {**pg_internal, "passed": layer1_pass},
    "layer2_exactly_once": {
        "auth": auth_diff, "approve": approve_diff, "capture": capture_diff,
        "passed": layer2_pass,
    },
    "passed": overall,
    # 참고값 — 판정에는 쓰지 않는다.
    "reference_post_convergence_inquiry": recovery["inquiry"],
}
with open("$RESULT_FILE", "w") as f:
    json.dump(result, f, indent=2)
print(json.dumps({"passed": overall, "layer1": layer1_pass, "layer2": layer2_pass}, indent=2))
EOF

log "Load phase done. Run scripts/run-settlement-verify.sh next."
