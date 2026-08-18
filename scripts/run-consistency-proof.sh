#!/usr/bin/env bash
# run-consistency-proof.sh — "장애를 맞고도 불일치 0건"을 한 번에 증명한다.
#
# 인증·FDS·승인 세 단계에 4종 장애를 균일 확률로 주입하고(매입은 대조군), 재시도 없이
# UNKNOWN → 조회로만 수렴시킨 뒤, 카드사가 독립 생성한 정산 파일과 대사해 불일치 0을 확인한다.
#
#   판정  정산파일 5-case 대사   MISSING_ON_CARD / MISSING_ON_PG / AMOUNT / STATUS / AGGREGATE
#
# PG가 자기 장부를 자기가 검사하는 것(내부 불변식·복식부기 균형)은 판정에서 뺐다. 그쪽은
# 우리 코드의 가정 위에서 도는 자기 진술이라, 깨지면 대사에서도 반드시 드러난다.
# 판정은 외부 근거 하나로만 한다 — 카드사가 자기 데이터로 만든 CSV.
#
# 대사 자체는 run-settlement-verify.sh를 그대로 재사용한다 — 판정 로직이 두 벌이 되면
# 언젠가 갈라지고, 그때 어느 쪽을 믿을지가 애매해진다.
#
# 함께 뽑는 것: 단계별 유입 경로(/admin/metrics/funnel).
# "이 단계에 들어온 건 중 직전 단계가 바로 성공한 건 몇, UNKNOWN이었다가 확정돼서 온 건 몇"을
# 가른다. 불일치 0이라는 결과만으로는 복구 경로가 실제로 일을 했는지 보이지 않기 때문이다.
# 장애를 주입해 놓고 복구 유입이 0이면 그건 정합성 증명이 아니라 장애가 안 걸린 것이다.
#
# 사용법: ./run-consistency-proof.sh [prob] [tps] [duration] [stages]
#   예) ./run-consistency-proof.sh 0.027 150 2m                 (기본값)
#       ./run-consistency-proof.sh 0.027 150 2m auth            (인증에만 주입)
#       ./run-consistency-proof.sh 0.027 150 2m approve,capture (승인·매입에 주입)
#
# stages는 auth / fds / approve / capture 중 콤마로 고른다. 고르지 않은 단계는 대조군이 된다.
# 장애를 하나도 안 맞는 단계를 하나는 남겨두는 게 좋다 — 이상이 보일 때 주입한 장애 탓인지
# 우리 쪽 문제인지 가르는 기준선이 사라진다.

set -euo pipefail

# 없는 도구는 여기서 잡는다. 안 그러면 DB 초기화 + 빌드 + 부하까지 다 돌고 나서 마지막
# 판정 단계에서 죽는다 — 5분을 쓰고 결과를 못 받는 데다 원인이 스크롤 저 위에 남는다.
# docker/k6는 사용자가 설치해야 하는 것이고, curl/python3은 보통 OS에 딸려온다.
for cmd in docker k6 curl python3; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "필요한 명령이 없습니다: $cmd"; exit 1; }
done

PROB=${1:-0.027}
TPS=${2:-150}
DURATION=${3:-2m}
FAIL_STAGES=${4:-auth,fds,approve}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/../results"
K6_SCRIPT="$SCRIPT_DIR/../k6/consistency_proof.js"

mkdir -p "$RESULTS_DIR"
STAMP=$(date +%Y%m%d_%H%M%S)
STAGE_TAG=$(echo "$FAIL_STAGES" | tr ',' '-')
RESULT_FILE="$RESULTS_DIR/${STAMP}_consistency_proof_p${PROB}_t${TPS}_d${DURATION}_${STAGE_TAG}.json"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

log() { echo "[$(date +%H:%M:%S)] $*"; }

# 응답을 파일로 받는다. 부하가 커지면 audit 키 목록이 수십만 자가 되어
# 셸 변수나 argv로 넘기면 ARG_MAX에 걸린다.
fetch() { curl -sf "$1" -o "$2"; }

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
log "=== 2. Clear failure rules + reset recovery metrics ==="
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  curl -sf -X DELETE "$url/admin/failure" >/dev/null
done
curl -sf -X POST "$PAYMENT_URL/admin/metrics/recovery/reset" >/dev/null

# ── 3. 부하 (setup이 주입, teardown이 해제) ─────────────────────────────────
log "=== 3. Run k6 chaos load (tps=$TPS duration=$DURATION prob=$PROB) ==="
log "    failure on: $FAIL_STAGES"
k6 run \
  -e PAYMENT="$PAYMENT_URL" -e CARD_A="$CARD_A_URL" -e CARD_B="$CARD_B_URL" -e FDS="$FDS_URL" \
  -e TPS="$TPS" -e DURATION="$DURATION" -e PROB="$PROB" -e FAIL_STAGES="$FAIL_STAGES" \
  ${PRE_VUS:+-e PRE_VUS="$PRE_VUS"} ${MAX_VUS:+-e MAX_VUS="$MAX_VUS"} \
  -e SUMMARY_OUT="$WORK/k6.json" \
  "$K6_SCRIPT"

# ── 4. 장애 해제 재확인 ────────────────────────────────────────────────────
log "=== 4. Ensure all failure rules cleared ==="
for url in "$PAYMENT_URL" "$CARD_A_URL" "$CARD_B_URL" "$FDS_URL"; do
  curl -sf -X DELETE "$url/admin/failure" >/dev/null
done

# ── 5. 수렴 게이트 ─────────────────────────────────────────────────────────
# 미확정 건이 남은 채로 대사에 들어가면 "아직 안 정해진 것"을 불일치로 판정하게 된다.
# /admin/convergence는 UNKNOWN뿐 아니라 웹훅 잔량과 PROCESSING 멱등키까지 본다.
log "=== 5. Convergence gate ==="
GATE_MAX_ITER=120
i=0
while true; do
  fetch "$PAYMENT_URL/admin/convergence" "$WORK/convergence.json"
  if python3 -c "import json,sys; sys.exit(0 if json.load(open('$WORK/convergence.json'))['converged'] else 1)"; then
    log "Converged: $(cat "$WORK/convergence.json")"
    break
  fi
  i=$((i+1))
  log "  round $i: $(cat "$WORK/convergence.json")"
  if (( i >= GATE_MAX_ITER )); then
    log "WARNING: convergence gate exhausted after $i attempts"
    log "  $(cat "$WORK/convergence.json")"
    break
  fi
  curl -sf -X POST "$PAYMENT_URL/admin/scheduler/run-now" >/dev/null
  sleep 5
done

# ── 6. 단계별 유입 경로 + 복구 활동량 ──────────────────────────────────────
log "=== 6. Stage funnel & recovery activity ==="
fetch "$PAYMENT_URL/admin/metrics/funnel" "$WORK/funnel.json"
fetch "$PAYMENT_URL/admin/metrics/recovery" "$WORK/recovery.json"

# ── 7. 대사 → 청산 → 정산 → 지급 ───────────────────────────────────────────
log "=== 7. Reconciliation / clearing / settlement / payout ==="
SETTLEMENT_RESULT_FILE="$WORK/settlement.json" "$SCRIPT_DIR/run-settlement-verify.sh"

# ── 8. 판정 및 기록 ────────────────────────────────────────────────────────
log "=== 8. Verdict -> $RESULT_FILE ==="
python3 - "$WORK" "$RESULT_FILE" "$PROB" "$TPS" "$DURATION" <<'EOF'
import json, sys

work, result_file, prob, tps, duration = sys.argv[1:6]
load = lambda name: json.load(open(f"{work}/{name}.json"))

k6         = load("k6")
funnel     = load("funnel")
recovery   = load("recovery")
settlement = load("settlement")

reconciled = settlement["layer4_settlement_reconciliation"]["passed"]

# 복구가 실제로 일어났는가. 장애를 주입해 놓고 이게 0이면 "불일치 0"은 정합성의 증거가
# 아니라 장애가 안 걸렸다는 뜻이다. 그래서 판정에 넣는다.
recovered_ok = sum(s["okViaInquiry"] for s in funnel["stages"])
chaos_effective = recovered_ok > 0

verdict = reconciled

result = {
    "scenario": {
        "tps": int(tps), "duration": duration,
        "triggerProbabilityPerType": float(prob),
        "failureTypes": ["TIMEOUT_BEFORE_PROCESS", "TIMEOUT_AFTER_PROCESS",
                         "ERROR_500", "CONNECT_FAILURE"],
        "failureStages": k6["params"]["failureStages"],
        "controlStages": k6["params"]["controlStages"],
        "retryModel": "none (all ambiguous outcomes -> UNKNOWN -> inquiry)",
        "merchantResume": "re-sends the same idempotent request until the phase is decided",
    },
    "passed": verdict,
    "settlementReconciliation": settlement["layer4_settlement_reconciliation"],
    # 판정에는 쓰지 않는다. 대사가 FAIL일 때 어디서 깨졌는지 좁히는 참고 자료다.
    "referenceLedger": settlement["layer3_ledger"],
    "stageFunnel": funnel,
    "chaosEffective": chaos_effective,
    "recoveredThroughInquiry": recovered_ok,
    "merchantView": k6["merchantView"],
    "loadValidity": k6["validity"],
    "referenceInquiryActivity": recovery["inquiry"],
}
json.dump(result, open(result_file, "w"), indent=2)

# ── 출력 ──────────────────────────────────────────────────────────────────
n = lambda v: f"{v:,}"

# UNKNOWN을 따로 세는 이유: 확정되고 나면 상태는 SUCCEEDED/FAIL 하나뿐이라, 한 번에 끝난 건과
# UNKNOWN을 거쳐 조회로 확정된 건이 DB에서 똑같이 생겼다. was_unknown 플래그가 그걸 가른다.
# 성공으로 확정된 것(okViaInquiry) + 실패로 확정된 것(failViaInquiry) + 아직 미확정(unresolved).
print("\n=== 단계별 처리 (DB 기준 · 수렴 후) ===")
print(f"  {'단계':<9}{'유입':>9}{'UNKNOWN':>10}{'성공':>9}{'  = 즉시 + 복구':<19}"
      f"{'실패':>8}{'  = 즉시 + 복구':<19}{'미확정':>7}")
for s in funnel["stages"]:
    if s["total"] == 0:
        continue
    unknown = s["okViaInquiry"] + s["failViaInquiry"] + s["unresolved"]
    ok_split   = f"  = {n(s['okDirect'])} + {n(s['okViaInquiry'])}"
    fail_split = f"  = {n(s['failDirect'])} + {n(s['failViaInquiry'])}"
    print(f"  {s['stage']:<9}{n(s['total']):>9}{n(unknown):>10}{n(s['ok']):>9}{ok_split:<19}"
          f"{n(s['fail']):>8}{fail_split:<19}{n(s['unresolved'] + s['inFlight']):>7}")

print("\n  유입 경로 — 이 단계에 들어온 건은 직전 단계가 어떻게 끝난 건인가")
for s in funnel["stages"]:
    e = s["enteredFrom"]
    if not e or s["total"] == 0:
        continue
    print(f"    {s['stage']:<8} <- {e['previousStage']:<8} "
          f"직전 즉시성공 {n(e['afterDirectSuccess']):>8} · "
          f"직전 복구확정 {n(e['afterInquiryRecovered']):>7} · 고아 {e['orphan']}")

mv = k6["merchantView"]
print(f"\n=== 가맹점이 본 것 (부하 중) ===")
print(f"  유입 {n(mv['started'])}건 중 매입까지 완주 {n(mv['completed'])}건 ({mv['completedPct']}%)")
for name, st in mv["stages"].items():
    wait = st["resumeWaitMs"]
    tail = f" · 재개 대기 p50 {wait['p50']}ms p95 {wait['p95']}ms" if wait else ""
    print(f"    {name:<8} 도달 {n(st['reached']):>7} · 성공 {n(st['ok']):>7} "
          f"(즉시 {n(st['directOk'])} + 재개 {n(st['resumedOk'])}) · "
          f"실패 {n(st['failed'])} · 포기 {n(st['abandoned'])}{tail}")

v = k6["validity"]
if v["loadGeneratorSaturated"]:
    print(f"\n  !! 부하 생성기 포화 — 이 런은 {tps} TPS의 결과가 아니다")
    print(f"     dropped={v['droppedIterations']} httpError={v['httpErrorTotal']} ({v['httpErrorPct']}%)")
    print(f"     PRE_VUS를 올리거나 TPS를 낮춰서 다시 재야 한다.")

mark = lambda ok: "PASS" if ok else "FAIL"
rec = settlement["layer4_settlement_reconciliation"]
CASES = [("missingOnCardCount", "카드사에만 있음"), ("missingOnPgCount", "PG에만 있음"),
         ("amountMismatchCount", "금액 불일치"), ("statusMismatchCount", "상태 불일치"),
         ("aggregateCount", "총액 불일치")]
disc = lambda v: sum(v[k] for k, _ in CASES)

print("\n=== 판정 — 정산파일 5-case 대사 ===")
print(f"  {'':<16}{'CARD_CORP_A':>13}{'CARD_CORP_B':>13}")
for key, label in CASES:
    print(f"  {label:<16}{n(rec['card_a'][key]):>13}{n(rec['card_b'][key]):>13}")
print(f"  {'합계':<16}{n(disc(rec['card_a'])):>13}{n(disc(rec['card_b'])):>13}")
print(f"\n  대사 불일치 0건                    {mark(verdict)}")
print(f"  장애가 실제로 걸렸는가              {mark(chaos_effective)}   (조회로 확정된 건 {n(recovered_ok)})")
print(f"\n  불일치 0건: {'증명됨' if verdict else '증명 실패'}")
EOF

log ""
log "Saved to $RESULT_FILE"
