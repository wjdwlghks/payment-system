import http from 'k6/http';
import { sleep, check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// ── 메트릭 ──────────────────────────────────────────────────
const paymentOk      = new Counter('payment_ok');       // FDS_READY
const paymentUnknown = new Counter('payment_unknown');   // UNKNOWN_AUTH / UNKNOWN_FDS
const paymentFail    = new Counter('payment_fail');      // AUTH_FAILED / FDS_FAILED / HTTP 에러

const captureOk      = new Counter('capture_ok');        // DONE
const captureUnknown = new Counter('capture_unknown');   // UNKNOWN_CAPTURE
const captureFail    = new Counter('capture_fail');      // CAPTURE_FAILED / HTTP 에러

const refundOk       = new Counter('refund_ok');         // SUCCEEDED
const refundUnknown  = new Counter('refund_unknown');    // UNKNOWN → InquiryScheduler resolve 예정
const refundFail     = new Counter('refund_fail');       // FAIL / HTTP 에러

// ── 서비스 주소 ──────────────────────────────────────────────
const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const CARD    = __ENV.CARD    || 'http://localhost:8084';
const FDS     = __ENV.FDS     || 'http://localhost:8083';

const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };
// 4개 룰 × triggerProbability=0.027 → 단계별 장애율 ~10%, 전체 iteration 장애 경험률 ~30%
const PROB      = 0.027;
const REMAINING = 9999;

// ── 장애 룰 등록 헬퍼 ────────────────────────────────────────
// serverFailures: 카드/FDS 서버에 주입 (TIMEOUT_BEFORE, TIMEOUT_AFTER, ERROR_500)
// connectFailure: payment RestClient에 주입 (CONNECT_FAILURE) → retry 대상
function injectServerFailures(baseUrl, endpoint) {
  ['TIMEOUT_BEFORE_PROCESS', 'TIMEOUT_AFTER_PROCESS', 'ERROR_500'].forEach(failure => {
    http.post(`${baseUrl}/admin/failure`, JSON.stringify({
      endpoint,
      failure,
      remaining: REMAINING,
      triggerProbability: PROB,
    }), JSON_HDR);
  });
}

function injectConnectFailure(paymentAlias) {
  http.post(`${PAYMENT}/admin/failure`, JSON.stringify({
    endpoint: paymentAlias,
    failure: 'CONNECT_FAILURE',
    remaining: REMAINING,
    triggerProbability: PROB,
  }), JSON_HDR);
}

// ── 실행 설정 ────────────────────────────────────────────────
export const options = {
  scenarios: {
    chaos: {
      executor: 'constant-vus',
      vus: 5,
      duration: '2m',
    },
  },
};

// ── setup: 전 단계 장애 주입 ─────────────────────────────────
// 서버 장애(TIMEOUT_BEFORE, TIMEOUT_AFTER, ERROR_500): 카드/FDS 서버에 주입
// CONNECT_FAILURE: payment RestClient 인터셉터에 주입 → Resilience4j retry 3회 대상
export function setup() {
  injectServerFailures(CARD, 'auth');
  injectServerFailures(FDS,  'fds_check');
  injectServerFailures(CARD, 'capture');
  injectServerFailures(CARD, 'refund');

  injectConnectFailure('card_auth');
  injectConnectFailure('fds_check');
  injectConnectFailure('card_capture');
  injectConnectFailure('card_refund');

  console.log('[setup] all failures injected (prob=0.027 each, 4 types × 4 stages)');
}

// ── teardown: 전체 해제 ──────────────────────────────────────
export function teardown() {
  http.del(`${CARD}/admin/failure`);
  http.del(`${FDS}/admin/failure`);
  http.del(`${PAYMENT}/admin/failure`);
  console.log('[teardown] cleared all failures');
}

// ── 메인 루프 ────────────────────────────────────────────────
export default function () {
  const merchantId = `chaos-${String(__VU).padStart(3, '0')}`;
  const orderId    = `chaos-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const amount     = 10000;
  const tag        = { tags: { vu: String(__VU) } };

  // ── 1) Auth + FDS ────────────────────────────────────────
  const authRes = http.post(
    `${PAYMENT}/v1/payment`,
    JSON.stringify({ orderId, merchantId, amount, cardCompany: 'CARD_CORP_A' }),
    { ...JSON_HDR, ...tag, timeout: '15s' }
  );

  if (!check(authRes, { 'auth 200': r => r.status === 200 })) {
    paymentFail.add(1);
    return;
  }

  const authStatus = authRes.json('status');
  const paymentKey = authRes.json('paymentKey');

  if (!paymentKey || authStatus === 'AUTH_FAILED' || authStatus === 'FDS_FAILED') {
    paymentFail.add(1);
    return;
  }
  if (typeof authStatus === 'string' && authStatus.includes('UNKNOWN')) {
    paymentUnknown.add(1);
    return;
  }
  paymentOk.add(1);  // FDS_READY

  sleep(0.1);

  // ── 2) Confirm (Capture) ─────────────────────────────────
  const confirmRes = http.post(
    `${PAYMENT}/v1/payment/${paymentKey}/confirm`,
    null,
    { ...JSON_HDR, ...tag, timeout: '15s' }
  );

  if (!check(confirmRes, { 'confirm 200': r => r.status === 200 })) {
    captureFail.add(1);
    return;
  }

  const piStatus = confirmRes.json('status');
  if (piStatus === 'DONE') {
    captureOk.add(1);
  } else if (typeof piStatus === 'string' && piStatus.includes('UNKNOWN')) {
    captureUnknown.add(1);
    return;
  } else {
    captureFail.add(1);
    return;
  }

  sleep(0.2);

  // ── 3) Refund (30% 확률) ─────────────────────────────────
  if (Math.random() >= 0.3) return;

  const refundKey = `rfk-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const refundRes = http.post(
    `${PAYMENT}/v1/payment/refund`,
    JSON.stringify({ paymentKey, refundKey, amount }),
    { ...JSON_HDR, ...tag, timeout: '30s' }
  );

  if (!check(refundRes, { 'refund 200': r => r.status === 200 })) {
    refundFail.add(1);
    return;
  }

  const refundStatus = refundRes.json('status');
  if (refundStatus === 'SUCCEEDED') {
    refundOk.add(1);
  } else if (refundStatus === 'UNKNOWN') {
    refundUnknown.add(1);
  } else {
    refundFail.add(1);
  }
}

// ── 최종 summary ─────────────────────────────────────────────
export function handleSummary(data) {
  const m   = data.metrics;
  const get = name => (m[name] ? m[name].values.count : 0);

  const totalPayment = get('payment_ok') + get('payment_unknown') + get('payment_fail');
  const totalCapture = get('capture_ok') + get('capture_unknown') + get('capture_fail');
  const totalRefund  = get('refund_ok')  + get('refund_unknown')  + get('refund_fail');

  // retry / inquiry 메트릭 조회
  const recovery = http.get(`${PAYMENT}/admin/metrics/recovery`).json();

  console.log('\n========= Chaos Test Summary =========');
  console.log(`Payment  total=${totalPayment}  ok=${get('payment_ok')}  unknown=${get('payment_unknown')}  fail=${get('payment_fail')}`);
  console.log(`Capture  total=${totalCapture}  ok=${get('capture_ok')}  unknown=${get('capture_unknown')}  fail=${get('capture_fail')}`);
  console.log(`Refund   total=${totalRefund}   ok=${get('refund_ok')}   unknown=${get('refund_unknown')}  fail=${get('refund_fail')}`);

  console.log('\n--- Retry Stats ---');
  for (const [name, s] of Object.entries(recovery.retry)) {
    if (s.attempts > 0) {
      console.log(`  ${name}: attempts=${s.attempts}  succeededAfterRetry=${s.succeededAfterRetry}  failedAfterRetry=${s.failedAfterRetry}`);
    }
  }

  console.log('\n--- Inquiry Stats ---');
  for (const [type, s] of Object.entries(recovery.inquiry)) {
    if (s.total > 0) {
      console.log(`  ${type}: total=${s.total}  success=${s.success}  failed=${s.failed}  notFound=${s.notFound}  inProgress=${s.inProgress}`);
    }
  }
  console.log('======================================\n');

  return {
    'refund-chaos-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
