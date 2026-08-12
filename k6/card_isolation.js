import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Trend, Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// 카드사별 분리 측정
const latA  = new Trend('latency_a', true);   // CARD_CORP_A (지연 주입)
const latB  = new Trend('latency_b', true);   // CARD_CORP_B (정상)
const okA   = new Counter('ok_a');
const okB   = new Counter('ok_b');
const failA = new Counter('fail_a');
const failB = new Counter('fail_b');

const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const CARD_A  = __ENV.CARD_A  || 'http://localhost:8084';
const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    iso: { executor: 'constant-vus', vus: 100, duration: '1m' },
  },
};

// CARD_CORP_A에만 지속 지연(SLOW_SUCCESS 2s) 주입, CARD_CORP_B는 정상
export function setup() {
  ['auth', 'approve'].forEach(ep => {
    http.post(`${CARD_A}/admin/failure`, JSON.stringify({
      endpoint: ep,
      failure: 'SLOW_SUCCESS',
      remaining: 999999,
      triggerProbability: 1.0,
    }), JSON_HDR);
  });
  console.log('[setup] SLOW_SUCCESS(2s) injected on CARD_CORP_A auth+approve only');
}

export function teardown() {
  http.del(`${CARD_A}/admin/failure`);
  console.log('[teardown] cleared');
}

export default function () {
  const isA = (__VU % 2 === 1);
  const company = isA ? 'CARD_CORP_A' : 'CARD_CORP_B';
  const merchantId = `iso-${String(__VU).padStart(3, '0')}`;
  const orderId    = `iso-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;

  const t0 = Date.now();

  const authRes = http.post(
    `${PAYMENT}/v1/payment`,
    JSON.stringify({ orderId, merchantId, amount: 10000, cardCompany: company }),
    { ...JSON_HDR, timeout: '30s' }
  );

  let success = false;
  if (authRes.status === 200) {
    const pk = authRes.json('paymentKey');
    const st = authRes.json('status');
    if (pk && st === 'FDS_PASSED') {
      const confirmRes = http.post(
        `${PAYMENT}/v1/payment/${pk}/approve`, null,
        { ...JSON_HDR, timeout: '30s' }
      );
      success = confirmRes.status === 200 && confirmRes.json('status') === 'APPROVED';
    }
  }

  const elapsed = Date.now() - t0;

  if (isA) {
    latA.add(elapsed);
    success ? okA.add(1) : failA.add(1);
  } else {
    latB.add(elapsed);
    success ? okB.add(1) : failB.add(1);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, field) => (m[name] ? (m[name].values[field] ?? m[name].values.count) : 0);

  console.log('\n========= Card Isolation Summary =========');
  console.log('CARD_CORP_A (지연 주입):');
  console.log(`  ok=${v('ok_a','count')}  fail=${v('fail_a','count')}  p95=${Math.round(v('latency_a','p(95)'))}ms  med=${Math.round(v('latency_a','med'))}ms`);
  console.log('CARD_CORP_B (정상):');
  console.log(`  ok=${v('ok_b','count')}  fail=${v('fail_b','count')}  p95=${Math.round(v('latency_b','p(95)'))}ms  med=${Math.round(v('latency_b','med'))}ms`);
  console.log('=========================================\n');

  const file = __ENV.SUMMARY_FILE || 'isolation-summary.json';
  return {
    [file]: JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
