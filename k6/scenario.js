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
const confirmUnknown     = new Counter('confirm_unknown');      // 2xx + UNKNOWN_* 상태
const confirmFailedHttp  = new Counter('confirm_failed_http'); // 5xx
const confirmFailedOther = new Counter('confirm_failed_other');

// ── 실행 설정 ────────────────────────────────────────────────
export const options = {
  scenarios: {
    stage1: {
      executor: 'constant-vus',
      vus:      parseInt(__ENV.VUS)  || 5,
      duration: __ENV.DURATION       || '2m',
    },
  },
  thresholds: {
    iterations: ['count>500'],
  },
};

const MERCHANT_BASE = __ENV.MERCHANT_BASE || 'http://localhost:8081';
const MERCHANT_ID   = __ENV.MERCHANT_ID   || 'merchant-001';

// ── 메인 시나리오 ────────────────────────────────────────────
export default function () {
  const orderId = `order-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const amount  = 10000;

  // 1) Auth
  const authRes = http.post(
    `${MERCHANT_BASE}/api/payments`,
    JSON.stringify({ merchantId: MERCHANT_ID, orderId, amount }),
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'auth' } }
  );

  if (authRes.status >= 500) { authFailedHttp.add(1);  return; }
  if (authRes.status >= 400) { authFailedOther.add(1); return; }

  authSucceeded.add(1);
  const paymentKey = authRes.json('paymentKey');
  if (!paymentKey) return;

  sleep(0.1); // 카드 입력 시뮬레이션

  // 2) Confirm (FDS + Capture)
  const confirmRes = http.post(
    `${MERCHANT_BASE}/api/payments/${paymentKey}/confirm`,
    null,
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'confirm' } }
  );

  if (confirmRes.status >= 500) { confirmFailedHttp.add(1);  return; }
  if (confirmRes.status >= 400) { confirmFailedOther.add(1); return; }

  const piStatus = confirmRes.json('status');
  if (piStatus === 'DONE') {
    confirmDone.add(1);
  } else if (typeof piStatus === 'string' && piStatus.startsWith('UNKNOWN')) {
    confirmUnknown.add(1);
  } else {
    confirmFailedOther.add(1);
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
