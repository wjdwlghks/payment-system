#!/usr/bin/env bash
# run-payment-refund-chaos.sh
#
# 시나리오: 50 VU, 2분, 결제:환불 ≈ 10:3(30%), 장애 위치 auth/fds/capture,
#           4종 장애(TIMEOUT_BEFORE_PROCESS/TIMEOUT_AFTER_PROCESS/ERROR_500/CONNECT_FAILURE)를
#           각 요청에 독립적으로 균일 확률로 주입 (k6/payment_refund_chaos.js).
#
# 이 스크립트는 재시도가 없는 현재 모델(모든 모호한 결과는 UNKNOWN→inquiry) 전제로,
# 다음만 측정한다:
#   - 수렴 시간: 측정하지 않음 (run-now로 강제 드레인 후 게이트로만 사용)
#   - 유계성(backlog 추세): 측정하지 않음
#   - 정합성: PG 내부 불변식 / PG↔Card exactly-once / 원장 복식부기 / 정산파일 재조정
#     네 계층만 수집해 PASS/FAIL로 판정한다.
#
# 사용법: ./run-payment-refund-chaos.sh [prob] [vus] [duration]
#   prob     : 4종 장애 각각의 독립 트리거 확률 (default 0.027, k6 스크립트 기본과 동일)
#   vus      : virtual users (default 50)
#   duration : k6 steady-state duration (default 2m)

set -euo pipefail

PROB=${1:-0.027}
VUS=${2:-50}
DURATION=${3:-2m}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
K6_SCRIPT="$(dirname "$0")/../k6/payment_refund_chaos.js"

mkdir -p "$RESULTS_DIR"
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_payment_refund_chaos_consistency_p${PROB}_v${VUS}_d${DURATION}.json"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ── 1. DB 초기화 + 기동 ────────────────────────────────────────────────────
log "=== 1. DB reset & build ==="
docker compose down -v
docker compose up -d --build

log "Waiting for services to be ready..."
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  until curl -sf "$url/admin/failure" >/dev/null 2>&1; do
    sleep 3
  done
done
log "Services ready."

# ── 2. 장애 해제 + 메트릭 리셋 ──────────────────────────────────────────────
log "=== 2. Clear failure rules + reset metrics ==="
curl -sf -X DELETE "$PAYMENT_URL/admin/failure"
curl -sf -X DELETE "$CARD_A_URL/admin/failure"
curl -sf -X DELETE "$CARD_B_URL/admin/failure"
curl -sf -X DELETE "$FDS_URL/admin/failure"
curl -sf -X POST   "$PAYMENT_URL/admin/metrics/recovery/reset"

# ── 3. k6 실행 (부하 스크립트가 자체적으로 setup()에서 4종×3위치 균일 랜덤 주입, teardown()에서 해제) ──
log "=== 3. Run k6 chaos load (vus=$VUS duration=$DURATION prob=$PROB) ==="
k6 run \
  -e PAYMENT="$PAYMENT_URL" \
  -e CARD_A="$CARD_A_URL" \
  -e CARD_B="$CARD_B_URL" \
  -e FDS="$FDS_URL" \
  -e VUS="$VUS" \
  -e DURATION="$DURATION" \
  -e PROB="$PROB" \
  "$K6_SCRIPT"

# ── 4. 장애 해제 (k6 teardown이 이미 해제하지만 안전하게 재확인) ─────────────
log "=== 4. Ensure all failure rules cleared ==="
curl -sf -X DELETE "$PAYMENT_URL/admin/failure" >/dev/null
curl -sf -X DELETE "$CARD_A_URL/admin/failure" >/dev/null
curl -sf -X DELETE "$CARD_B_URL/admin/failure" >/dev/null
curl -sf -X DELETE "$FDS_URL/admin/failure" >/dev/null

# ── 5. 수렴 게이트 (시간은 기록하지 않음 — 정합성 검사 전 UNKNOWN 잔존을 반드시 비운다) ──
log "=== 5. Convergence gate (forcing drain via scheduler/run-now; not timed) ==="
GATE_MAX_ITER=60   # 안전 상한 (5s * 60 = 최대 5분 시도, 그래도 도달 못하면 경고 후 진행)
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
    log "WARNING: convergence gate exhausted after $i attempts — proceeding with unknownRemaining=$UNKNOWN_REMAINING"
    break
  fi
  curl -sf -X POST "$PAYMENT_URL/admin/scheduler/run-now" >/dev/null
  sleep 5
done

# ── 5-1. 수렴 후 누적 inquiry 메트릭 (참고값 — k6 handleSummary는 k6 종료 시점의
#         중간 스냅샷이라 게이트가 마저 처리한 몫이 빠짐. 여기서 최종 누적치를 기록한다.
#         PASS/FAIL 판정에는 사용하지 않는다.) ─────────────────────────────────
log "=== 5-1. Post-convergence cumulative inquiry metrics (reference only) ==="
RECOVERY_POST_CONVERGENCE=$(curl -sf "$PAYMENT_URL/admin/metrics/recovery")
log "Recovery (post-convergence): $RECOVERY_POST_CONVERGENCE"

# ── 6. 정산 파일 생성 (카드사별 독립 ground truth) ──────────────────────────
log "=== 6. Generate settlement files ==="
SETTLEMENT_FILE_A=$(curl -sf -X POST "$CARD_A_URL/admin/settlements/generate")
log "Settlement file A: $SETTLEMENT_FILE_A"
SETTLEMENT_FILE_B=$(curl -sf -X POST "$CARD_B_URL/admin/settlements/generate")
log "Settlement file B: $SETTLEMENT_FILE_B"

# ── 7. 재조정 ingest + validate (계층 4) ────────────────────────────────────
log "=== 7. Reconciliation ingest & validate ==="
BUSINESS_DATE=$(date +%Y-%m-%d)

INGEST_A=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/ingest" \
  -H 'Content-Type: application/json' \
  -d "{\"filePath\":\"/recon-files/$SETTLEMENT_FILE_A\",\"cardCompany\":\"CARD_CORP_A\",\"businessDate\":\"$BUSINESS_DATE\"}")
BATCH_A=$(echo "$INGEST_A" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconBatchId'])")
VALIDATE_A=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/$BATCH_A/validate")
log "Validate A: $VALIDATE_A"

INGEST_B=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/ingest" \
  -H 'Content-Type: application/json' \
  -d "{\"filePath\":\"/recon-files/$SETTLEMENT_FILE_B\",\"cardCompany\":\"CARD_CORP_B\",\"businessDate\":\"$BUSINESS_DATE\"}")
BATCH_B=$(echo "$INGEST_B" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconBatchId'])")
VALIDATE_B=$(curl -sf -X POST "$PAYMENT_URL/admin/reconciliation/$BATCH_B/validate")
log "Validate B: $VALIDATE_B"

# ── 7-1. 카드사 -> PG 정산(SETTLEMENT) ──────────────────────────────────────
log "=== 7-1. Settlement run ==="
SETTLEMENT_RUN=$(curl -sf -X POST "$PAYMENT_URL/admin/settlement-run")
log "Settlement run: $SETTLEMENT_RUN"

# ── 7-2. PG -> 가맹점 출금(PAYOUT) ───────────────────────────────────────────
log "=== 7-2. Payout per merchant ==="
for i in $(seq 1 "$VUS"); do
  MID="chaos-$(printf '%03d' "$i")"
  curl -sf -X POST "$PAYMENT_URL/admin/payouts/$MID" >/dev/null
done

# ── 8. 정합성 계층 1: PG 내부 불변식 ─────────────────────────────────────────
log "=== 8. Layer 1 — PG internal invariants ==="
PG_INTERNAL=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal")
log "PG internal: $PG_INTERNAL"

# ── 9. 정합성 계층 2: PG ↔ Card exactly-once (cardRequestRef 집합 대칭차) ────
log "=== 9. Layer 2 — PG <-> Card exactly-once (cardRequestRef set diff) ==="
PG_AUTH_KEYS=$(curl -sf "$PAYMENT_URL/admin/audit/auth-keys")
PG_CAPTURE_KEYS=$(curl -sf "$PAYMENT_URL/admin/audit/capture-keys")
CARD_A_AUTH_KEYS=$(curl -sf "$CARD_A_URL/admin/audit/auth-keys")
CARD_A_CAPTURE_KEYS=$(curl -sf "$CARD_A_URL/admin/audit/capture-keys")
CARD_B_AUTH_KEYS=$(curl -sf "$CARD_B_URL/admin/audit/auth-keys")
CARD_B_CAPTURE_KEYS=$(curl -sf "$CARD_B_URL/admin/audit/capture-keys")

AUTH_DIFF=$(python3 - <<EOF
import json
pg   = set(json.loads('''$PG_AUTH_KEYS'''))
card = set(json.loads('''$CARD_A_AUTH_KEYS''')) | set(json.loads('''$CARD_B_AUTH_KEYS'''))
diff = pg.symmetric_difference(card)
print(json.dumps({"pg_only": list(pg - card), "card_only": list(card - pg), "diff_count": len(diff)}))
EOF
)

CAPTURE_DIFF=$(python3 - <<EOF
import json
pg   = set(json.loads('''$PG_CAPTURE_KEYS'''))
card = set(json.loads('''$CARD_A_CAPTURE_KEYS''')) | set(json.loads('''$CARD_B_CAPTURE_KEYS'''))
diff = pg.symmetric_difference(card)
print(json.dumps({"pg_only": list(pg - card), "card_only": list(card - pg), "diff_count": len(diff)}))
EOF
)

log "AUTH exactly-once diff:    $AUTH_DIFF"
log "CAPTURE exactly-once diff: $CAPTURE_DIFF"

# ── 10. 정합성 계층 3: 원장 복식부기 ─────────────────────────────────────────
log "=== 10. Layer 3 — Ledger double-entry balance ==="
LEDGER=$(curl -sf "$PAYMENT_URL/admin/verify/ledger")
log "Ledger: $LEDGER"

# ── 11. 종합 판정 및 결과 기록 ───────────────────────────────────────────────
log "=== 11. Recording consistency report to $RESULT_FILE ==="

python3 - <<EOF
import json

pg_internal   = json.loads('''$PG_INTERNAL''')
auth_diff     = json.loads('''$AUTH_DIFF''')
capture_diff  = json.loads('''$CAPTURE_DIFF''')
ledger        = json.loads('''$LEDGER''')
validate_a    = json.loads('''$VALIDATE_A''')
validate_b    = json.loads('''$VALIDATE_B''')
recovery_post_convergence = json.loads('''$RECOVERY_POST_CONVERGENCE''')

def recon_clean(v):
    return (v["missingOnCardCount"] == 0 and v["missingOnPgCount"] == 0
            and v["amountMismatchCount"] == 0 and v["statusMismatchCount"] == 0
            and v["aggregateCount"] == 0)

layer1_pass = pg_internal["passed"]
layer2_pass = auth_diff["diff_count"] == 0 and capture_diff["diff_count"] == 0
layer3_pass = ledger["passed"]
layer4_pass = recon_clean(validate_a) and recon_clean(validate_b)

overall_pass = layer1_pass and layer2_pass and layer3_pass and layer4_pass

result = {
    "scenario": {
        "vus": $VUS,
        "duration": "$DURATION",
        "trigger_probability_per_type": $PROB,
        "failure_locations": ["auth", "fds", "capture"],
        "failure_types": ["TIMEOUT_BEFORE_PROCESS", "TIMEOUT_AFTER_PROCESS", "ERROR_500", "CONNECT_FAILURE"],
        "payment_refund_ratio": "10:3 (30% refund)",
        "retry_model": "none (all ambiguous outcomes -> UNKNOWN -> inquiry)",
    },
    "consistency": {
        "layer1_pg_internal": {**pg_internal, "passed": layer1_pass},
        "layer2_exactly_once": {
            "auth": auth_diff,
            "capture": capture_diff,
            "passed": layer2_pass,
        },
        "layer3_ledger": {**ledger, "passed": layer3_pass},
        "layer4_settlement_reconciliation": {
            "card_a": validate_a,
            "card_b": validate_b,
            "passed": layer4_pass,
        },
    },
    "passed": overall_pass,
    # 참고값 — PASS/FAIL 판정에는 사용하지 않음.
    # 수렴 게이트가 끝난 뒤의 누적 inquiry 통계로, k6 handleSummary가 찍는 k6 종료
    # 시점 스냅샷과 달리 이 값이 실제 최종 처리량이다(k6 unknown_* 카운터와 대략 일치해야 함).
    "reference_post_convergence_inquiry": recovery_post_convergence["inquiry"],
}

with open("$RESULT_FILE", "w") as f:
    json.dump(result, f, indent=2)

print(json.dumps({"passed": overall_pass,
                   "layer1": layer1_pass, "layer2": layer2_pass,
                   "layer3": layer3_pass, "layer4": layer4_pass}, indent=2))
EOF

log "Done. Report: $RESULT_FILE"
