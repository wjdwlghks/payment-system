#!/usr/bin/env bash
# run-latency.sh — UNKNOWN → 결제완료 지연 측정
#
# 사용자 지연은 "결제 요청 → APPROVED 인지"까지다. 매입은 포함하지 않는다 —
# 사용자는 승인 시점에 결제완료를 본다. 다만 가맹점은 승인 즉시 매입을 발사하므로
# 매입 경로도 부하를 받고, 매입 지연은 별도 지표로 따로 나온다.
#
# 측정은 가맹점 서버가 한다(요청 발신 ~ APPROVED 인지). payment 내부 계측이 아니라
# 블랙박스 실측이라 웹훅 배달 시간까지 전부 포함된다.
#
# ⚠ run-payment-chaos.sh 와 결정적으로 다른 점: /admin/scheduler/run-now 를 부르지 않는다.
#   스케줄러를 수동으로 때리면 "복구에 걸린 시간"이 아니라 "얼마나 자주 때렸나"를 재게 된다.
#   대신 스케줄러 자기 주기대로 수렴할 때까지 그냥 기다린다.
#
# 사용법: ./run-latency.sh [prob] [tps] [duration]

set -euo pipefail

PROB=${1:-0.027}
TPS=${2:-20}
DURATION=${3:-2m}

MERCHANT_URL="http://localhost:8081"
PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
K6_SCRIPT="$(dirname "$0")/../k6/payment_latency.js"

mkdir -p "$RESULTS_DIR"
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_latency_p${PROB}_t${TPS}_d${DURATION}.json"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ── 1. DB 초기화 + 기동 ────────────────────────────────────────────────────
log "=== 1. DB reset & build ==="
docker compose down -v
docker compose up -d --build

log "Waiting for services..."
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  until curl -sf "$url/admin/failure" >/dev/null 2>&1; do sleep 3; done
done
until curl -sf "$MERCHANT_URL/admin/latency" >/dev/null 2>&1; do sleep 3; done
log "Services ready."

# ── 2. 부하 ────────────────────────────────────────────────────────────────
log "=== 2. Run k6 latency load (tps=$TPS duration=$DURATION prob=$PROB) ==="
k6 run \
  -e MERCHANT="$MERCHANT_URL" -e PAYMENT="$PAYMENT_URL" \
  -e CARD_A="$CARD_A_URL" -e CARD_B="$CARD_B_URL" -e FDS="$FDS_URL" \
  -e TPS="$TPS" -e DURATION="$DURATION" -e PROB="$PROB" \
  "$K6_SCRIPT"

log "=== 3. Ensure failure rules cleared ==="
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  curl -sf -X DELETE "$url/admin/failure" >/dev/null
done

# ── 4. 자연 수렴 대기 (run-now 없음) ───────────────────────────────────────
# 남은 UNKNOWN은 InquiryScheduler(10s)가 자기 주기로 해소한다. 그 시간이 곧 측정 대상이므로
# 절대 앞당기지 않는다. inFlight가 0이 되어야 분포에 가장 느린 건들이 들어온다.
log "=== 4. Wait for natural convergence (NO run-now) ==="
WAIT_MAX=${WAIT_MAX:-180}
elapsed=0
while (( elapsed < WAIT_MAX )); do
  CONV=$(curl -sf "$PAYMENT_URL/admin/convergence")
  IN_FLIGHT=$(curl -sf "$MERCHANT_URL/admin/latency" | \
    python3 -c "import sys,json; print(json.load(sys.stdin)['inFlight'])")
  CONVERGED=$(echo "$CONV" | python3 -c "import sys,json; print(json.load(sys.stdin)['converged'])")

  if [[ "$CONVERGED" == "True" && "$IN_FLIGHT" == "0" ]]; then
    log "Converged after ${elapsed}s (payment converged, merchant inFlight=0)."
    break
  fi
  log "  waiting... ${elapsed}s  converged=$CONVERGED inFlight=$IN_FLIGHT"
  sleep 10
  elapsed=$((elapsed+10))
done

if (( elapsed >= WAIT_MAX )); then
  log "WARNING: ${WAIT_MAX}s 안에 수렴하지 않았다 — 아래 분포는 낙관 편향(가장 느린 건들이 빠짐)."
fi

# ── 5. 결과 ────────────────────────────────────────────────────────────────
log "=== 5. Latency report ==="
LATENCY=$(curl -sf "$MERCHANT_URL/admin/latency")
CONVERGENCE=$(curl -sf "$PAYMENT_URL/admin/convergence")
PG_INTERNAL=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal")
RECOVERY=$(curl -sf "$PAYMENT_URL/admin/metrics/recovery")

python3 - <<EOF > "$RESULT_FILE"
import json
print(json.dumps({
    "params":      {"prob": "$PROB", "tps": "$TPS", "duration": "$DURATION"},
    "latency":     json.loads('''$LATENCY'''),
    "convergence": json.loads('''$CONVERGENCE'''),
    "pgInternal":  json.loads('''$PG_INTERNAL'''),
    "recovery":    json.loads('''$RECOVERY'''),
}, indent=2, ensure_ascii=False))
EOF

cat "$RESULT_FILE"
log "Saved to $RESULT_FILE"

log ""
log "판독 순서:"
log "  1) latency.inFlight == 0 이어야 한다. 아니면 가장 느린 건들이 분포에서 빠진 것."
log "  2) latency.workerQueueDepth 가 0 근처여야 한다. 쌓였으면 merchant가 병목이라 그 런은 버린다."
log "  3) 그 다음에 userLatency.normal(대조군) vs userLatency.viaUnknown(UNKNOWN 경유)를 비교한다."
