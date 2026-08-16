#!/usr/bin/env bash
# run-sync-latency.sh — 동기 응답 지연 측정 (요청 → 응답 수신)
#
# 성공이든 UNKNOWN이든 응답을 받으면 거기서 측정이 끝난다. 복구 경로는 안 잰다.
# run-latency.sh 와 재는 대상이 다르다:
#
#   run-latency.sh       요청 → APPROVED 인지   (사용자 체감. 복구 경로 포함, merchant 계측)
#   run-sync-latency.sh  요청 → 응답 수신       (동기 경로만. payment 직접 호출)
#
# 병목을 찾는 게 목적이다. 동기 응답은 기대값이 명확해서(소켓 타임아웃 3s가 상한)
# **기대값을 넘는 만큼이 곧 우리 쪽 큐잉**이 된다. 사용자 체감 지연에는 설정 상수가
# 대부분을 차지해 그 신호가 묻힌다.
#
# 사용법: ./run-sync-latency.sh [prob] [tps] [duration]

set -euo pipefail

PROB=${1:-0.027}
TPS=${2:-150}
DURATION=${3:-2m}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
K6_SCRIPT="$(dirname "$0")/../k6/payment_sync_latency.js"

mkdir -p "$RESULTS_DIR"
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_sync_p${PROB}_t${TPS}_d${DURATION}.json"

log() { echo "[$(date +%H:%M:%S)] $*"; }

log "=== 1. DB reset & build ==="
docker compose down -v
docker compose up -d --build

log "Waiting for services..."
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  until curl -sf "$url/admin/failure" >/dev/null 2>&1; do sleep 3; done
done
log "Services ready."

K6_SUMMARY=$(mktemp)

log "=== 2. Run k6 sync-latency load (tps=$TPS duration=$DURATION prob=$PROB) ==="
k6 run \
  -e PAYMENT="$PAYMENT_URL" -e CARD_A="$CARD_A_URL" -e CARD_B="$CARD_B_URL" -e FDS="$FDS_URL" \
  -e TPS="$TPS" -e DURATION="$DURATION" -e PROB="$PROB" \
  -e SUMMARY_OUT="$K6_SUMMARY" \
  "$K6_SCRIPT"

log "=== 3. Ensure failure rules cleared ==="
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  curl -sf -X DELETE "$url/admin/failure" >/dev/null
done

# 남은 UNKNOWN은 이 측정의 대상이 아니지만, 정합성이 깨진 채로 끝나지 않았는지는 확인한다.
# run-now는 부르지 않는다 — 여기서 앞당길 이유가 없다.
log "=== 4. Wait for convergence (sanity only, NO run-now) ==="
WAIT_MAX=${WAIT_MAX:-180}
elapsed=0
while (( elapsed < WAIT_MAX )); do
  CONVERGED=$(curl -sf "$PAYMENT_URL/admin/convergence" | \
    python3 -c "import sys,json; print(json.load(sys.stdin)['converged'])")
  [[ "$CONVERGED" == "True" ]] && { log "Converged after ${elapsed}s."; break; }
  log "  waiting... ${elapsed}s"
  sleep 10
  elapsed=$((elapsed+10))
done

log "=== 5. Report ==="
CONVERGENCE=$(curl -sf "$PAYMENT_URL/admin/convergence")
PG_INTERNAL=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal")

python3 - <<EOF > "$RESULT_FILE"
import json

with open("$K6_SUMMARY") as f:
    summary = json.load(f)

summary["convergence"] = json.loads('''$CONVERGENCE''')
summary["pgInternal"]  = json.loads('''$PG_INTERNAL''')

# 기대 상한을 넘은 만큼이 우리 쪽 대기다. UNKNOWN_* 응답은 소켓 타임아웃 시점에
# 즉시 반환되므로 3,000ms를 넘을 이유가 없다.
bound = summary["socketTimeoutMs"]
excess = {}
for name, s in summary["syncLatency"].items():
    if "UNKNOWN" in name:
        excess[name] = {"p95_excess_ms": s["p95"] - bound, "p99_excess_ms": s["p99"] - bound}
summary["queueingExcess"] = excess

print(json.dumps(summary, indent=2, ensure_ascii=False))
EOF

cat "$RESULT_FILE"
log "Saved to $RESULT_FILE"
log ""
log "판독: queueingExcess 가 0 근처여야 한다. 크면 그만큼이 Tomcat/커넥션풀/DB 대기다."
log "     capture_* 는 장애 미주입 대조군 — 여기가 느려지면 순수하게 우리 쪽 문제다."
