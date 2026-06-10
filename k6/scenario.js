import http from 'k6/http';
import { sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// ── 커스텀 메트릭 ────────────────────────────────────────────
const authSucceeded    = new Counter('auth_succeeded');
const authFailedHttp   = new Counter('auth_failed_http');   // 5xx
const authFailedOther  = new Counter('auth_failed_other');  // 4xx 등

const confirmDone        = new Counter('confirm_done');
const confirmUnknown     = new Counter('confirm_unknown');
const confirmFailedHttp  = new Counter('confirm_failed_http');
const confirmFailedOther = new Counter('confirm_failed_other');

// ── 실행 설정 ────────────────────────────────────────────────
const VUS      = parseInt(__ENV.VUS)      || 5;
const WARMUP   = __ENV.WARMUP             || '30s';
const DURATION = __ENV.DURATION           || '2m';
const WARMDOWN = __ENV.WARMDOWN           || '30s';

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      stages: [
        { duration: WARMUP,   target: VUS }, // warm-up: 0 → VUS
        { duration: DURATION, target: VUS }, // steady state
        { duration: WARMDOWN, target: 0   }, // warm-down: VUS → 0
      ],
    },
  },
  thresholds: {
    iterations: ['count>500'],
  },
};

const MERCHANT_BASE = __ENV.MERCHANT_BASE || 'http://localhost:8081';

// VU 홀수 → CARD_CORP_A (장애 주입 대상), 짝수 → CARD_CORP_B (정상)
function cardCompany() {
  return (__VU % 2 === 1) ? 'CARD_CORP_A' : 'CARD_CORP_B';
}

// ── 메인 시나리오 ────────────────────────────────────────────
export default function () {
  const company    = cardCompany();
  const merchantId = `merchant-${String(__VU).padStart(3, '0')}`;
  const orderId    = `order-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const amount     = 10000;

  // 1) Auth
  const authRes = http.post(
    `${MERCHANT_BASE}/api/payments`,
    JSON.stringify({ merchantId, orderId, amount, cardCompany: company }),
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'auth', cardCompany: company } }
  );

  if (authRes.status >= 500) { authFailedHttp.add(1, { cardCompany: company });  return; }
  if (authRes.status >= 400) { authFailedOther.add(1, { cardCompany: company }); return; }

  authSucceeded.add(1, { cardCompany: company });
  const paymentKey = authRes.json('paymentKey');
  if (!paymentKey) return;

  sleep(0.1);

  // 2) Confirm (Capture)
  const confirmRes = http.post(
    `${MERCHANT_BASE}/api/payments/${paymentKey}/confirm`,
    null,
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'confirm', cardCompany: company } }
  );

  if (confirmRes.status >= 500) { confirmFailedHttp.add(1, { cardCompany: company });  return; }
  if (confirmRes.status >= 400) { confirmFailedOther.add(1, { cardCompany: company }); return; }

  const piStatus = confirmRes.json('status');
  if (piStatus === 'DONE') {
    confirmDone.add(1, { cardCompany: company });
  } else if (typeof piStatus === 'string' && piStatus.startsWith('UNKNOWN')) {
    confirmUnknown.add(1, { cardCompany: company });
  } else {
    confirmFailedOther.add(1, { cardCompany: company });
  }
}

// ── 종료 summary ─────────────────────────────────────────────
export function handleSummary(data) {
  const summaryFile = __ENV.SUMMARY_FILE || 'stage1-summary.json';
  return {
    [summaryFile]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
