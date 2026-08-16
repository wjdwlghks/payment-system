#!/usr/bin/env bash
# run-settlement-verify.sh — 정산 검증 테스트 (대사 → 청산 → 정산 → 지급)
#
# 부하 테스트(run-payment-chaos.sh)가 남긴 매입 데이터를 대상으로,
# 카드사가 독립 생성한 정산 파일과 대사하고 그 뒤 정산·지급까지 태운다.
#
# 청산은 별도 단계가 아니다 — 대사(validate)가 불일치 0을 확인한 뒤에만 청산 기표를 한다.
# 그래서 실행 순서는 "대사 → (자동)청산 → 정산 → 지급"이고 이 순서를 바꿀 수 없다.
#
# 판정 계층:
#   Layer 4  정산파일 대사 5-case  (MISSING_ON_CARD / MISSING_ON_PG / AMOUNT / STATUS / AGGREGATE)
#   Layer 3  원장 복식부기 균형 + CARD_NETWORK_RECEIVABLE 잔액 0
#
# 주의: 대사가 청산까지 수행하므로 같은 데이터에 두 번 실행할 수 없다(carry-over로 abort된다).
#       부하 직후 1회 실행을 전제로 한다.
#
# 사용법: ./run-settlement-verify.sh [businessDate]

set -euo pipefail

# businessDate는 대사 대상 tx의 createdAt 범위(KST 하루)를 정한다.
# 부하와 검증이 자정을 사이에 두고 실행되면 오늘 날짜는 틀린다 — 매입 데이터에서 도출한다.
BUSINESS_DATE=${1:-}

PAYMENT_URL="http://localhost:8082"
CARD_A_URL="http://localhost:8084"
CARD_B_URL="http://localhost:8085"
RESULTS_DIR="$(dirname "$0")/../results"

mkdir -p "$RESULTS_DIR"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# curl -sf 는 5xx에서 본문을 삼키고 종료한다 — 실패 원인을 드러내기 위해 코드/본문을 분리해 받는다.
post_json() {  # $1=url  $2=body(optional)
  local url=$1 body=${2:-} out code
  if [[ -n "$body" ]]; then
    out=$(curl -s -w '\n%{http_code}' -X POST "$url" -H 'Content-Type: application/json' -d "$body")
  else
    out=$(curl -s -w '\n%{http_code}' -X POST "$url")
  fi
  code=$(echo "$out" | tail -1)
  body=$(echo "$out" | sed '$d')
  if [[ "$code" != 2* ]]; then
    log "ERROR: POST $url -> HTTP $code"
    log "  $body"
    return 1
  fi
  echo "$body"
}

if [[ -z "$BUSINESS_DATE" ]]; then
  BUSINESS_DATE=$(docker exec payment-mysql mysql -upayment -ppayment -D payment -sN -e \
    "SELECT DATE(CONVERT_TZ(MAX(created_at),'+00:00','+09:00')) FROM \`transaction\` WHERE type='CAPTURE';" 2>/dev/null)
  if [[ -z "$BUSINESS_DATE" || "$BUSINESS_DATE" == "NULL" ]]; then
    echo "ERROR: 매입 트랜잭션이 없다. 부하 스크립트를 먼저 실행할 것." >&2
    exit 1
  fi
fi

# 상위 스크립트(run-consistency-proof.sh)가 결과를 합칠 수 있도록 경로를 넘겨받을 수 있게 한다.
RESULT_FILE=${SETTLEMENT_RESULT_FILE:-"$RESULTS_DIR/$(date +%Y%m%d_%H%M%S)_settlement_verify_${BUSINESS_DATE}.json"}

# ── 0. 선행조건 — UNKNOWN이 남아 있으면 대사가 미확정 거래를 대상으로 삼는다 ──
log "=== 0. Preflight ==="
PREFLIGHT=$(curl -sf "$PAYMENT_URL/admin/verify/pg-internal")
UNKNOWN_REMAINING=$(echo "$PREFLIGHT" | python3 -c "import sys,json; print(json.load(sys.stdin)['unknownRemaining'])")
log "Preflight: $PREFLIGHT"
if [[ "$UNKNOWN_REMAINING" != "0" ]]; then
  log "ERROR: unknownRemaining=$UNKNOWN_REMAINING — 수렴 전이다. 부하 스크립트의 수렴 게이트를 먼저 통과시킬 것."
  exit 1
fi

# ── 1. 카드사별 정산 파일 생성 (PG가 아니라 카드사가 자기 매입 데이터로 만든다) ──
log "=== 1. Generate settlement files ==="
SETTLEMENT_FILE_A=$(post_json "$CARD_A_URL/admin/settlements/generate")
SETTLEMENT_FILE_B=$(post_json "$CARD_B_URL/admin/settlements/generate")
log "Settlement file A: $SETTLEMENT_FILE_A"
log "Settlement file B: $SETTLEMENT_FILE_B"

# ── 2. 대사 ingest + validate (불일치 0이면 여기서 청산까지 일어난다) ────────
log "=== 2. Reconciliation ingest & validate (business date: $BUSINESS_DATE) ==="
reconcile() {  # $1=file, $2=cardCompany
  local ingest batch
  ingest=$(post_json "$PAYMENT_URL/admin/reconciliation/ingest" \
    "{\"filePath\":\"/recon-files/$1\",\"cardCompany\":\"$2\",\"businessDate\":\"$BUSINESS_DATE\"}")
  batch=$(echo "$ingest" | python3 -c "import sys,json; print(json.load(sys.stdin)['reconBatchId'])")
  post_json "$PAYMENT_URL/admin/reconciliation/$batch/validate"
}
VALIDATE_A=$(reconcile "$SETTLEMENT_FILE_A" CARD_CORP_A)
log "Validate A: $VALIDATE_A"
VALIDATE_B=$(reconcile "$SETTLEMENT_FILE_B" CARD_CORP_B)
log "Validate B: $VALIDATE_B"

# ── 3. 카드사 → PG 정산 ────────────────────────────────────────────────────
log "=== 3. Settlement run ==="
SETTLEMENT_RUN=$(post_json "$PAYMENT_URL/admin/settlement-run")
log "Settlement run: $SETTLEMENT_RUN"

# ── 4. PG → 가맹점 지급 ────────────────────────────────────────────────────
# 가맹점 목록은 부하 형태에 의존하지 않도록 원장 계정에서 뽑는다.
log "=== 4. Payout per merchant ==="
MERCHANTS=$(docker exec payment-mysql mysql -upayment -ppayment -D payment -sN \
  -e "SELECT merchant_id FROM account WHERE account_type='MERCHANT_PENDING' AND merchant_id <> 'GLOBAL';" 2>/dev/null)
PAYOUT_COUNT=0
for MID in $MERCHANTS; do
  post_json "$PAYMENT_URL/admin/payouts/$MID" >/dev/null
  PAYOUT_COUNT=$((PAYOUT_COUNT+1))
done
log "Payout issued for $PAYOUT_COUNT merchants."

# ── 5. Layer 3 — 원장 ──────────────────────────────────────────────────────
log "=== 5. Layer 3 — Ledger ==="
LEDGER=$(curl -sf "$PAYMENT_URL/admin/verify/ledger")
log "Ledger: $LEDGER"

# ── 6. 판정 및 기록 ────────────────────────────────────────────────────────
log "=== 6. Recording verification report to $RESULT_FILE ==="
python3 - <<EOF
import json

validate_a = json.loads('''$VALIDATE_A''')
validate_b = json.loads('''$VALIDATE_B''')
ledger     = json.loads('''$LEDGER''')
settlement = json.loads('''$SETTLEMENT_RUN''')

def recon_clean(v):
    return (v["missingOnCardCount"] == 0 and v["missingOnPgCount"] == 0
            and v["amountMismatchCount"] == 0 and v["statusMismatchCount"] == 0
            and v["aggregateCount"] == 0)

layer4_pass = recon_clean(validate_a) and recon_clean(validate_b)

# 정산·지급까지 끝난 뒤이므로 미수금은 0이어야 한다 —
# 매입(차변) → 청산(수수료 대변) → 정산(순액 대변)이 모두 상계된 상태.
# 매입 실패건과 미매입 승인건은 애초에 기표가 없어 잔액에 영향을 주지 않는다.
receivable_settled = ledger["cardNetworkReceivableBalance"] == 0
layer3_pass = ledger["unbalancedPostings"] == 0 and receivable_settled

overall = layer3_pass and layer4_pass

result = {
    "business_date": "$BUSINESS_DATE",
    "settlement_run": settlement,
    "payout_merchants": $PAYOUT_COUNT,
    "layer3_ledger": {
        **ledger,
        "receivableSettled": receivable_settled,
        "passed": layer3_pass,
    },
    "layer4_settlement_reconciliation": {
        "card_a": validate_a,
        "card_b": validate_b,
        "passed": layer4_pass,
    },
    "passed": overall,
}
with open("$RESULT_FILE", "w") as f:
    json.dump(result, f, indent=2)
print(json.dumps({"passed": overall, "layer3": layer3_pass, "layer4": layer4_pass}, indent=2))
EOF
