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
# 부하는 기본적으로 **카드사 한 곳**에만 간다. 두 곳에 나눠 보내면 카드사당 실부하가
# 절반이 되어 Bulkhead·커넥션 풀이 얼마나 찼는지가 달라지고, 격리 설정을 바꿔가며
# 비교할 기준선이 되지 못한다. CARDS=CARD_CORP_A,CARD_CORP_B 로 되돌릴 수 있다.
#
# 격리 A/B: 아래 4개 환경변수는 compose(payment 컨테이너)와 k6 요약 양쪽으로 함께 나간다.
#   CARD_BULKHEAD_MAX / CARD_CB_FAILURE_RATE / CARD_CB_SLOW_RATE / CARD_CB_MIN_CALLS
#
# 사용법: ./run-sync-latency.sh [prob] [tps] [duration]
#   예) 기준선          ./run-sync-latency.sh 0.027 150 2m
#       격리 끈 대조군   CARD_BULKHEAD_MAX=100000 CARD_CB_MIN_CALLS=100000000 \
#                       ./run-sync-latency.sh 0.027 150 2m

set -euo pipefail

PROB=${1:-0.027}
TPS=${2:-150}
DURATION=${3:-2m}
CARDS=${CARDS:-CARD_CORP_A}
SICK_CARD=${SICK_CARD:-}
SICK_FAILURE=${SICK_FAILURE:-SLOW_SUCCESS}

# compose.yaml이 ${...:-기본값}으로 읽으므로 export해야 payment 컨테이너에 실린다.
export CARD_BULKHEAD_MAX=${CARD_BULKHEAD_MAX:-80}
export CARD_CB_FAILURE_RATE=${CARD_CB_FAILURE_RATE:-50}
export CARD_CB_SLOW_RATE=${CARD_CB_SLOW_RATE:-50}
export CARD_CB_MIN_CALLS=${CARD_CB_MIN_CALLS:-100}
export CARD_MAX_CONN_PER_ROUTE=${CARD_MAX_CONN_PER_ROUTE:-128}
export CARD_MAX_CONN_TOTAL=${CARD_MAX_CONN_TOTAL:-256}
# true면 카드사가 풀 하나를 공유한다 = 격리 이전 상태. 전파 관측용.
export CARD_SHARED_POOL=${CARD_SHARED_POOL:-false}

# Bulkhead 퍼밋이 커넥션 풀보다 크면 거절 지점이 풀로 옮겨가면서 즉시 실패가
# UNKNOWN으로 바뀐다 — 측정이 조용히 다른 걸 재게 되므로 여기서 막는다.
#
# 단, 10000 이상은 "Bulkhead를 껐다"는 뜻이고 그때는 풀이 유일한 상한이 되는 게
# 의도한 동작이다(격리 OFF 대조군). 그래서 그 경우만 통과시킨다.
if (( CARD_BULKHEAD_MAX < 10000 )) && (( CARD_BULKHEAD_MAX >= CARD_MAX_CONN_PER_ROUTE )); then
  echo "ERROR: CARD_BULKHEAD_MAX($CARD_BULKHEAD_MAX) >= CARD_MAX_CONN_PER_ROUTE($CARD_MAX_CONN_PER_ROUTE)." >&2
  echo "       퍼밋을 올렸으면 CARD_MAX_CONN_PER_ROUTE도 그 위로 올려야 한다." >&2
  exit 1
fi

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"
RESULTS_DIR="$(dirname "$0")/../results"
K6_SCRIPT="$(dirname "$0")/../k6/payment_sync_latency.js"

mkdir -p "$RESULTS_DIR"
# 파일명에 카드사 수와 Bulkhead 값을 박는다 — A/B 두 파일을 나중에 구분하려고.
NCARDS=$(awk -F, '{print NF}' <<<"$CARDS")
RESULT_FILE="$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_sync_p${PROB}_t${TPS}_d${DURATION}_c${NCARDS}_bh${CARD_BULKHEAD_MAX}.json"

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

log "=== 2. Run k6 sync-latency load (tps=$TPS duration=$DURATION prob=$PROB cards=$CARDS) ==="
log "    isolation: bulkhead=$CARD_BULKHEAD_MAX pool=$CARD_MAX_CONN_PER_ROUTE/route sharedPool=$CARD_SHARED_POOL cbFailureRate=$CARD_CB_FAILURE_RATE cbSlowRate=$CARD_CB_SLOW_RATE cbMinCalls=$CARD_CB_MIN_CALLS"
[[ -n "$SICK_CARD" ]] && log "    sick: $SICK_CARD ($SICK_FAILURE)"
k6 run \
  -e PAYMENT="$PAYMENT_URL" -e CARD_A="$CARD_A_URL" -e CARD_B="$CARD_B_URL" -e FDS="$FDS_URL" \
  -e TPS="$TPS" -e DURATION="$DURATION" -e PROB="$PROB" -e CARDS="$CARDS" \
  -e SICK_CARD="$SICK_CARD" -e SICK_FAILURE="$SICK_FAILURE" \
  -e CARD_BULKHEAD_MAX="$CARD_BULKHEAD_MAX" \
  -e CARD_MAX_CONN_PER_ROUTE="$CARD_MAX_CONN_PER_ROUTE" \
  -e CARD_SHARED_POOL="$CARD_SHARED_POOL" \
  ${PRE_VUS:+-e PRE_VUS="$PRE_VUS"} ${MAX_VUS:+-e MAX_VUS="$MAX_VUS"} \
  -e CARD_CB_FAILURE_RATE="$CARD_CB_FAILURE_RATE" \
  -e CARD_CB_SLOW_RATE="$CARD_CB_SLOW_RATE" \
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
log "=== 기준선 ==="
python3 - <<EOF
import json
s = json.load(open("$RESULT_FILE"))
h, t = s["headline"], s["throughput"]
print(f"  처리량(API)   {h['rps']} req/s   성공 {h['okRps']} req/s ({h['okRate']}%)")
print(f"  처리량(결제)  {h['completedRps']}/s   (유입 {h['offeredRps']}/s 중 완주 {h['completedPct']}% · {t['captured']}/{t['started']})")
print(f"  p95           {h['syncP95Ms']}ms   (p99 {h['syncP99Ms']}ms)")
print(f"  이탈          unknown {t['unknown']} · rejected {t['rejected']} · httpError {t['httpError']}")

v = s.get("validity", {})
if v.get("hostStalled"):
    print(f"\n  !!! 호스트 정지 — 이 런은 지연도 부하 프로파일도 전부 무효다")
    print(f"      max={v['maxLatencyMs']}ms (요청 타임아웃 15,000ms보다 큼) · 런 시간 {v['testRunSec']}s")
    print(f"      PRE_VUS를 줄여서(2000 이하) 다시 재야 한다.")
if v.get("loadGeneratorSaturated"):
    print(f"\n  !! 부하 생성기 포화 — 이 런의 지연 수치는 신뢰할 수 없다")
    print(f"     dropped={v['droppedIterations']} httpError={v['httpError']} ({v['httpErrorPct']}%) vuHeadroom={v['vuHeadroom']}")
    print(f"     PRE_VUS를 올리거나 TPS를 낮춰서 다시 재야 한다.")

by = s.get("byCard", {})
if len(by) > 1:
    print(f"\n  카드사별 (이론 완주율 {s['theoreticalCompletedPct']}%)")
    for name, c in by.items():
        tag = "아픔" if c["sick"] else "정상"
        lat = c["latency"] or {}
        print(f"    {name:<12} [{tag}] 처리 {c.get('apiResponses')}건 (성공 {c.get('apiOk')} · {c['okRps']}/s)"
              f"  완주 {c['completedRps']}/s ({c['completedPct']}%)")
        print(f"    {'':<12}        p95 {lat.get('p95')}ms  p50 {lat.get('p50')}ms"
              f"  unknown {c['unknown']} rejected {c['rejected']}")
EOF
log ""
log "판독: queueingExcess 가 0 근처여야 한다. 크면 그만큼이 Tomcat/커넥션풀/DB 대기다."
log "     capture_* 는 장애 미주입 대조군 — 여기가 느려지면 순수하게 우리 쪽 문제다."
