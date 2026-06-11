import http from 'k6/http';
import { sleep, check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// ── 메트릭 ──────────────────────────────────────────────────
const captureOk       = new Counter('capture_ok');
const captureUnknown  = new Counter('capture_unknown');
const captureFail     = new Counter('capture_fail');

const refundOk        = new Counter('refund_ok');
const refundUnknown   = new Counter('refund_unknown');  // InquiryScheduler가 resolve 예정
const refundFail      = new Counter('refund_fail');

// ── 서비스 주소 ──────────────────────────────────────────────
const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const CARD_A  = __ENV.CARD_A  || 'http://localhost:8084';  // 장애 주입 대상

const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

// ── 실행 설정 ────────────────────────────────────────────────
export const options = {
  scenarios: {
    chaos: {
      executor: 'constant-vus',
      vus: 50,
      duration: '2m',
    },
  },
  // 장애 환경에서도 환불 시도의 70% 이상은 SUCCEEDED 또는 UNKNOWN(회복 예정)이어야 함
  thresholds: {
    'refund_fail': ['count<30'],
  },
};

// ── setup: 장애 주입 ─────────────────────────────────────────
// CARD_CORP_A 환불 엔드포인트에 두 가지 장애를 겹쳐서 등록
//   · TIMEOUT_BEFORE_PROCESS (70%, 200회): 처리 없이 타임아웃 → payment UNKNOWN
//   · ERROR_500              (50%, 150회): 즉시 500 반환     → payment FAIL
// consumeByAlias는 첫 번째 shouldTrigger() 룰을 적용하므로
// 약 70% UNKNOWN, ~15% FAIL(timeout 미적용 후 500 적용), ~15% 정상 분포
export function setup() {
  http.post(`${CARD_A}/admin/failure`, JSON.stringify({
    endpoint: 'refund',
    failure:  'TIMEOUT_BEFORE_PROCESS',
    remaining: 200,
    triggerProbability: 0.7,
  }), JSON_HDR);

  http.post(`${CARD_A}/admin/failure`, JSON.stringify({
    endpoint: 'refund',
    failure:  'ERROR_500',
    remaining: 150,
    triggerProbability: 0.5,
  }), JSON_HDR);

  console.log('[setup] refund failure injected on card-a');
}

// ── teardown: 장애 해제 ──────────────────────────────────────
export function teardown(data) {
  http.del(`${CARD_A}/admin/failure`);
  console.log('[teardown] cleared all failures on card-a');
}

// ── 메인 루프 ────────────────────────────────────────────────
export default function () {
  const merchantId = `chaos-${String(__VU).padStart(3, '0')}`;
  const orderId    = `chaos-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const amount     = 10000;

  // 모든 VU는 CARD_CORP_A 사용 (장애 주입 대상)
  const company = 'CARD_CORP_A';
  const tag     = { tags: { vu: String(__VU) } };

  // ── 1) Auth + FDS ────────────────────────────────────────
  const authRes = http.post(
    `${PAYMENT}/v1/payment`,
    JSON.stringify({ orderId, merchantId, amount, cardCompany: company }),
    { ...JSON_HDR, ...tag, timeout: '15s' }
  );
  if (!check(authRes, { 'auth 200': r => r.status === 200 })) return;
  const paymentKey = authRes.json('paymentKey');
  if (!paymentKey) return;

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
    return;  // UNKNOWN capture는 환불 대상 아님
  } else {
    captureFail.add(1);
    return;
  }

  sleep(0.2);

  // ── 3) Refund (30% 확률, 장애 주입 구간) ────────────────────
  if (Math.random() >= 0.3) return;

  // card-a에 TIMEOUT_BEFORE_PROCESS / ERROR_500 혼재 → UNKNOWN or FAIL
  const refundKey = `rfk-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const refundRes = http.post(
    `${PAYMENT}/v1/payment/refund`,
    JSON.stringify({ paymentKey, refundKey, amount }),
    { ...JSON_HDR, ...tag, timeout: '30s' }  // payment가 retry 소진 후 응답할 때까지 여유
  );

  if (!check(refundRes, { 'refund 200': r => r.status === 200 })) {
    refundFail.add(1);
    return;
  }

  const refundStatus = refundRes.json('status');
  if (refundStatus === 'SUCCEEDED') {
    refundOk.add(1);
  } else if (refundStatus === 'UNKNOWN') {
    // InquiryScheduler가 10초 이내에 resolve
    refundUnknown.add(1);
  } else {
    refundFail.add(1);
  }
}

// ── 최종 summary ─────────────────────────────────────────────
export function handleSummary(data) {
  const m = data.metrics;
  const get = name => (m[name] ? m[name].values.count : 0);

  const totalCapture = get('capture_ok') + get('capture_unknown') + get('capture_fail');
  const totalRefund  = get('refund_ok')  + get('refund_unknown')  + get('refund_fail');

  console.log('\n========= Chaos Test Summary =========');
  console.log(`Capture  total=${totalCapture}  ok=${get('capture_ok')}  unknown=${get('capture_unknown')}  fail=${get('capture_fail')}`);
  console.log(`Refund   total=${totalRefund}   ok=${get('refund_ok')}   unknown=${get('refund_unknown')} (scheduler resolve 예정)  fail=${get('refund_fail')}`);
  console.log('======================================\n');

  return {
    'refund-chaos-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
