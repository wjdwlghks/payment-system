import http from 'k6/http';
import { sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// ── 커스텀 메트릭 ────────────────────────────────────────────
const authSucceeded    = new Counter('auth_succeeded');
const authFailedHttp   = new Counter('auth_failed_http');   // 5xx
const authFailedOther  = new Counter('auth_failed_other');  // 4xx 등

const approveDone        = new Counter('approve_done');
const approveUnknown     = new Counter('approve_unknown');
const approveFailedHttp  = new Counter('approve_failed_http');
const approveFailedOther = new Counter('approve_failed_other');

const captureOk          = new Counter('capture_ok');
const captureUnknown     = new Counter('capture_unknown');
const captureFailed      = new Counter('capture_failed');

// ── 실행 설정 (TPS 기반) ────────────────────────────────────
// TPS=100 처럼 목표 초당 결제(iteration) 수를 직접 지정한다.
// preAllocatedVUs/maxVUs는 그 TPS를 유지하기 위해 k6가 내부적으로 쓰는 VU 풀 크기일 뿐,
// 부하의 단위가 아니다 — 응답이 느려지면 k6가 maxVUs까지 자동으로 VU를 늘려 목표 TPS를 맞춘다.
const TPS      = parseInt(__ENV.TPS)      || 100;
const DURATION = __ENV.DURATION           || '2m';
const PRE_VUS  = parseInt(__ENV.PRE_VUS)  || Math.max(10, TPS);
const MAX_VUS  = parseInt(__ENV.MAX_VUS)  || Math.max(50, TPS * 10);

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: TPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    iterations: ['count>500'],
  },
};

const PAYMENT_BASE = __ENV.PAYMENT_BASE || 'http://localhost:8082';

// VU 홀수 → CARD_CORP_A, 짝수 → CARD_CORP_B
function cardCompany() {
  return (__VU % 2 === 1) ? 'CARD_CORP_A' : 'CARD_CORP_B';
}

// ── 메인 시나리오 ────────────────────────────────────────────
export default function () {
  const company    = cardCompany();
  const merchantId = `merchant-${String(__VU).padStart(3, '0')}`;
  const orderId    = `order-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const amount     = 10000;

  // 1) Auth + FDS
  const authRes = http.post(
    `${PAYMENT_BASE}/v1/payment`,
    JSON.stringify({ merchantId, orderId, amount, cardCompany: company }),
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'auth', cardCompany: company } }
  );

  if (authRes.status >= 500) { authFailedHttp.add(1, { cardCompany: company });  return; }
  if (authRes.status >= 400) { authFailedOther.add(1, { cardCompany: company }); return; }

  authSucceeded.add(1, { cardCompany: company });
  const paymentKey = authRes.json('paymentKey');
  if (!paymentKey) return;

  // 2) Approve (승인)
  const approveRes = http.post(
    `${PAYMENT_BASE}/v1/payment/${paymentKey}/approve`,
    null,
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'approve', cardCompany: company } }
  );

  if (approveRes.status >= 500) { approveFailedHttp.add(1, { cardCompany: company });  return; }
  if (approveRes.status >= 400) { approveFailedOther.add(1, { cardCompany: company }); return; }

  const piStatus = approveRes.json('status');
  if (piStatus === 'APPROVED') {
    approveDone.add(1, { cardCompany: company });
  } else if (typeof piStatus === 'string' && piStatus.startsWith('UNKNOWN')) {
    approveUnknown.add(1, { cardCompany: company });
    return;
  } else {
    approveFailedOther.add(1, { cardCompany: company });
    return;
  }

  // 3) Capture (매입) — 가맹점이 승인완료 직후 건별로 요청한다.
  //    매입은 PaymentIntent 상태를 바꾸지 않으므로 captureStatus로 판정한다.
  const captureRes = http.post(
    `${PAYMENT_BASE}/v1/payment/${paymentKey}/capture`,
    null,
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'capture', cardCompany: company } }
  );

  if (captureRes.status >= 400) { captureFailed.add(1, { cardCompany: company }); return; }

  const captureStatus = captureRes.json('captureStatus');
  if (captureStatus === 'SUCCEEDED')    captureOk.add(1, { cardCompany: company });
  else if (captureStatus === 'UNKNOWN') captureUnknown.add(1, { cardCompany: company });
  else                                  captureFailed.add(1, { cardCompany: company });
}

// ── 종료 summary ─────────────────────────────────────────────
export function handleSummary(data) {
  const summaryFile = __ENV.SUMMARY_FILE || 'stage1-summary.json';

  const dropped = data.metrics.dropped_iterations
    ? data.metrics.dropped_iterations.values.count
    : 0;
  if (dropped > 0) {
    console.log(`\n⚠ dropped_iterations=${dropped} — maxVUs(${MAX_VUS})가 부족해 목표 TPS(${TPS})를 못 채웠을 수 있음. MAX_VUS를 늘려서 재시도 권장.\n`);
  }

  return {
    [summaryFile]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
