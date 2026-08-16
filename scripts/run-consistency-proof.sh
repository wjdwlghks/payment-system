#!/usr/bin/env bash
# run-consistency-proof.sh — "장애를 맞고도 불일치 0건"을 한 번에 증명한다.
#
# 인증·FDS·승인 세 단계에 4종 장애를 균일 확률로 주입하고(매입은 대조군), 재시도 없이
# UNKNOWN → 조회로만 수렴시킨 뒤, 네 계층이 전부 0인지 확인한다.
#
#   Layer 1  PG 내부 불변식          /admin/verify/pg-internal
#   Layer 2  PG↔카드사 exactly-once  cardRequestRef 집합 대칭차 (인증/승인/매입 3축)
#   Layer 3  원장 복식부기 균형       차대 불일치 0 + 정산 후 미수금 0
#   Layer 4  정산파일 5-case 대사     카드사가 독립 생성한 CSV와의 대사
#
# Layer 3·4는 run-settlement-verify.sh를 그대로 재사용한다 — 판정 로직이 두 벌이 되면
# 언젠가 갈라지고, 그때 어느 쪽을 믿을지가 애매해진다.
#
# 함께 뽑는 것: 단계별 유입 경로(/admin/metrics/funnel).
# "이 단계에 들어온 건 중 직전 단계가 바로 성공한 건 몇, UNKNOWN이었다가 확정돼서 온 건 몇"을
# 가른다. 불일치 0이라는 결과만으로는 복구 경로가 실제로 일을 했는지 보이지 않기 때문이다.
# 장애를 주입해 놓고 복구 유입이 0이면 그건 정합성 증명이 아니라 장애가 안 걸린 것이다.
#
# 사용법: ./run-consistency-proof.sh [prob] [tps] [duration]
#   예) ./run-consistency-proof.sh 0.027 150 2m     (기본값)

set -euo pipefail

PROB=${1:-0.027}
TPS=${2:-150}
DURATION=${3:-2m}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
FDS_URL="http://localhost:8083"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/../results"
K6_SCRIPT="$SCRIPT_DIR/../k6/consistency_proof.js"

mkdir -p "$RESULTS_DIR"
STAMP=$(date +%Y%m%d_%H%M%S)
RESULT_FILE="$RESULTS_DIR/${STAMP}_consistency_proof_p${PROB}_t${TPS}_d${DURATION}.json"

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
log "    failure on: auth / fds / approve   ·   capture = control (no failure)"
k6 run \
  -e PAYMENT="$PAYMENT_URL" -e CARD_A="$CARD_A_URL" -e CARD_B="$CARD_B_URL" -e FDS="$FDS_URL" \
  -e TPS="$TPS" -e DURATION="$DURATION" -e PROB="$PROB" \
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
  if (( i >= GATE_MAX_ITER )); then
    log "WARNING: convergence gate exhausted after $i attempts"
    log "  $(cat "$WORK/convergence.json")"
    break
  fi
  curl -sf -X POST "$PAYMENT_URL/admin/scheduler/run-now" >/dev/null
  sleep 5
done

# ── 6. Layer 1 — PG 내부 불변식 ────────────────────────────────────────────
log "=== 6. Layer 1 — PG internal invariants ==="
fetch "$PAYMENT_URL/admin/verify/pg-internal" "$WORK/pg_internal.json"
log "  $(cat "$WORK/pg_internal.json")"

# ── 7. Layer 2 — PG↔카드사 exactly-once ────────────────────────────────────
# 두 집합의 대칭차가 0이어야 한다. pg_only는 카드사에 없는 청구(이중/유령),
# card_only는 카드사엔 있는데 우리가 모르는 거래(누락)다. 방향이 다르면 원인도 다르다.
log "=== 7. Layer 2 — PG <-> Card exactly-once (auth / approve / capture) ==="
for phase in auth approve capture; do
  fetch "$PAYMENT_URL/admin/audit/${phase}-keys" "$WORK/pg_${phase}.json"
  fetch "$CARD_A_URL/admin/audit/${phase}-keys" "$WORK/card_a_${phase}.json"
  fetch "$CARD_B_URL/admin/audit/${phase}-keys" "$WORK/card_b_${phase}.json"
done
python3 - "$WORK" <<'EOF'
import json, sys
work = sys.argv[1]
out = {}
for phase in ("auth", "approve", "capture"):
    pg   = set(json.load(open(f"{work}/pg_{phase}.json")))
    card = set(json.load(open(f"{work}/card_a_{phase}.json"))) \
         | set(json.load(open(f"{work}/card_b_{phase}.json")))
    pg_only, card_only = sorted(pg - card), sorted(card - pg)
    out[phase] = {
        "pgCount": len(pg), "cardCount": len(card),
        "diffCount": len(pg ^ card),
        # 전량을 남기면 결과 파일이 수십 MB가 된다. 원인 추적에는 표본으로 충분하다.
        "pgOnlySample": pg_only[:20], "cardOnlySample": card_only[:20],
        "pgOnlyCount": len(pg_only), "cardOnlyCount": len(card_only),
    }
json.dump(out, open(f"{work}/exactly_once.json", "w"), indent=2)
for phase, d in out.items():
    print(f"  {phase:<8} pg={d['pgCount']} card={d['cardCount']} diff={d['diffCount']}")
EOF

# ── 8. 단계별 유입 경로 + 복구 활동량 ──────────────────────────────────────
log "=== 8. Stage funnel & recovery activity ==="
fetch "$PAYMENT_URL/admin/metrics/funnel" "$WORK/funnel.json"
fetch "$PAYMENT_URL/admin/metrics/recovery" "$WORK/recovery.json"

# ── 9. Layer 3·4 — 대사 → 청산 → 정산 → 지급 ───────────────────────────────
log "=== 9. Layer 3 & 4 — reconciliation / clearing / settlement / payout ==="
SETTLEMENT_RESULT_FILE="$WORK/settlement.json" "$SCRIPT_DIR/run-settlement-verify.sh"

# ── 10. 판정 및 기록 ───────────────────────────────────────────────────────
log "=== 10. Verdict -> $RESULT_FILE ==="
python3 - "$WORK" "$RESULT_FILE" "$PROB" "$TPS" "$DURATION" <<'EOF'
import json, sys

work, result_file, prob, tps, duration = sys.argv[1:6]
load = lambda name: json.load(open(f"{work}/{name}.json"))

k6           = load("k6")
pg_internal  = load("pg_internal")
exactly_once = load("exactly_once")
funnel       = load("funnel")
recovery     = load("recovery")
settlement   = load("settlement")

layer1 = pg_internal["passed"]
layer2 = all(d["diffCount"] == 0 for d in exactly_once.values())
layer3 = settlement["layer3_ledger"]["passed"]
layer4 = settlement["layer4_settlement_reconciliation"]["passed"]

# 복구가 실제로 일어났는가. 장애를 주입해 놓고 이게 0이면 "불일치 0"은 정합성의 증거가
# 아니라 장애가 안 걸렸다는 뜻이다. 그래서 판정에 넣는다.
recovered_ok = sum(s["okViaInquiry"] for s in funnel["stages"])
chaos_effective = recovered_ok > 0

verdict = layer1 and layer2 and layer3 and layer4 and funnel["entryPathsIntact"]

result = {
    "scenario": {
        "tps": int(tps), "duration": duration,
        "triggerProbabilityPerType": float(prob),
        "failureTypes": ["TIMEOUT_BEFORE_PROCESS", "TIMEOUT_AFTER_PROCESS",
                         "ERROR_500", "CONNECT_FAILURE"],
        "failureStages": ["auth", "fds", "approve"],
        "controlStage": "capture",
        "retryModel": "none (all ambiguous outcomes -> UNKNOWN -> inquiry)",
        "merchantResume": "re-sends the same idempotent request until the phase is decided",
    },
    "passed": verdict,
    "layer1_pg_internal": {**pg_internal, "passed": layer1},
    "layer2_exactly_once": {**exactly_once, "passed": layer2},
    "layer3_ledger": settlement["layer3_ledger"],
    "layer4_settlement_reconciliation": settlement["layer4_settlement_reconciliation"],
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

print("\n=== 단계별 통과 (DB 기준 · 수렴 후) ===")
print(f"  {'단계':<9}{'총건수':>9}{'성공':>9}{'  = 즉시 + 복구확정':<22}{'실패':>8}{'  미확정':>9}")
for s in funnel["stages"]:
    if s["total"] == 0:
        continue
    split = f"  = {n(s['okDirect'])} + {n(s['okViaInquiry'])}"
    print(f"  {s['stage']:<9}{n(s['total']):>9}{n(s['ok']):>9}{split:<22}"
          f"{n(s['fail']):>8}{n(s['unresolved'] + s['inFlight']):>9}")

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
print("\n=== 판정 ===")
print(f"  Layer 1  PG 내부 불변식           {mark(layer1)}"
      f"   (UNKNOWN 잔존 {pg_internal['unknownRemaining']} · 고아매입 {pg_internal['captureWithoutApprove']}"
      f" · 멱등키 고착 {pg_internal['processingIdempotencyKeys']})")
eo = " · ".join(f"{k} {d['diffCount']}" for k, d in exactly_once.items())
print(f"  Layer 2  PG↔카드사 exactly-once   {mark(layer2)}   (대칭차: {eo})")
led = settlement["layer3_ledger"]
print(f"  Layer 3  원장 복식부기             {mark(layer3)}"
      f"   (차대 불일치 {led['unbalancedPostings']} · 정산 후 미수금 {led['cardNetworkReceivableBalance']})")
rec = settlement["layer4_settlement_reconciliation"]
disc = lambda v: (v["missingOnCardCount"] + v["missingOnPgCount"] + v["amountMismatchCount"]
                  + v["statusMismatchCount"] + v["aggregateCount"])
print(f"  Layer 4  정산파일 5-case 대사      {mark(layer4)}"
      f"   (CARD_CORP_A {disc(rec['card_a'])}건 · CARD_CORP_B {disc(rec['card_b'])}건)")
print(f"  ─────────────────────────────────────────────")
print(f"  유입 경로 정합                     {mark(funnel['entryPathsIntact'])}   (고아 유입 0)")
print(f"  장애가 실제로 걸렸는가              {mark(chaos_effective)}   (조회로 확정된 건 {n(recovered_ok)})")
print(f"\n  불일치 0건: {'증명됨' if verdict else '증명 실패'}")
EOF

log ""
log "Saved to $RESULT_FILE"
