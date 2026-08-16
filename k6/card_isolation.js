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

// VU 수가 이 테스트의 성패를 가른다.
//
// 카드사별 커넥션 풀은 이미 분리되어 있어서, A가 느려도 B의 커넥션은 건드리지 못한다.
// **전파는 커넥션이 아니라 payment의 Tomcat 스레드(기본 200개)를 통해 일어난다** —
// A를 기다리는 요청이 그 공유 풀을 다 먹으면 B도 스레드를 못 얻는다.
//
// 그래서 VU가 적으면(예전 값 100 → A 50개) A가 스레드 50개만 잡아 200개 중 여유가 남고,
// 격리 장치가 있든 없든 B가 멀쩡해서 **A/B 비교 자체가 성립하지 않는다.**
// A쪽 VU가 Tomcat 풀(200)을 덮을 만큼은 되어야 한다.
const VUS      = parseInt(__ENV.VUS) || 400;
const DURATION = __ENV.DURATION || '1m';

// closed  — VU 고정. 각 VU가 "요청 → 응답 대기 → 다음 요청"을 반복하므로
//           도착률이 시스템 응답속도에 좌우된다. 빨리 실패할수록 부하가 늘어난다.
// open    — 도착률 고정. 응답이 느려도 요청은 계속 같은 속도로 들어온다.
//           실제 인터넷 트래픽의 모양이고, 두 설정을 같은 부하로 비교할 수 있다.
const MODE = __ENV.MODE || 'closed';
const TPS  = parseInt(__ENV.TPS) || 200;

const scenario = MODE === 'open'
  ? {
      executor: 'constant-arrival-rate',
      rate: TPS,
      timeUnit: '1s',
      duration: DURATION,
      // 느린 카드사에 묶이는 VU가 많아 넉넉히 깔아둔다. 런 중 증설되면
      // 초기화가 늦어 iteration이 drop되고 그만큼 부하가 빠진다.
      preAllocatedVUs: parseInt(__ENV.PRE_VUS) || 800,
      maxVUs: parseInt(__ENV.MAX_VUS) || 4000,
    }
  : { executor: 'constant-vus', vus: VUS, duration: DURATION };

export const options = {
  summaryTrendStats: ['count', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg'],
  scenarios: { iso: scenario },
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
  console.log(`[setup] SLOW_SUCCESS(2s) on CARD_CORP_A auth+approve only — vus=${VUS} dur=${DURATION}`);
}

export function teardown() {
  http.del(`${CARD_A}/admin/failure`);
  console.log('[teardown] cleared');
}

export default function () {
  // 개방 모델에서는 VU 번호로 가르면 안 된다. 느린 카드사에 배정된 VU가 오래 묶여 있어서
  // k6가 놀고 있는(= 대부분 정상 카드사쪽) VU를 더 자주 집게 되고, 트래픽 비율이 한쪽으로
  // 기운다. 매 iteration마다 동전을 던지면 그 편향이 없다.
  // 폐쇄 모델은 VU가 고정 배정이라 기존 방식 그대로 둔다.
  const isA = MODE === 'open' ? (Math.random() < 0.5) : (__VU % 2 === 1);
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
  const n = (name, field) => {
    const values = m[name] && m[name].values;
    if (!values) return 0;
    return Math.round(field ? (values[field] ?? 0) : values.count);
  };

  const side = (lat, ok, fail) => {
    const okCount = n(ok), failCount = n(fail), total = okCount + failCount;
    return {
      ok: okCount,
      fail: failCount,
      // 성공률이 격리의 최종 판정이다. B가 떨어지면 A의 장애가 전파된 것.
      successRate: total ? Math.round(1000 * okCount / total) / 10 : null,
      p50: n(lat, 'med'), p90: n(lat, 'p(90)'),
      p95: n(lat, 'p(95)'), p99: n(lat, 'p(99)'), max: n(lat, 'max'),
    };
  };

  const summary = {
    config: {
      mode: MODE,
      vus: MODE === 'open' ? `arrival ${TPS}/s` : VUS,
      duration: DURATION,
      droppedIterations: n('dropped_iterations'),
      // 어떤 설정으로 돌았는지 결과에 박아둔다. A/B 두 파일을 나중에 헷갈리지 않으려고.
      bulkheadMax:      __ENV.CARD_BULKHEAD_MAX    || '48 (default)',
      cbFailureRate:    __ENV.CARD_CB_FAILURE_RATE || '50 (default)',
      cbSlowCallRate:   __ENV.CARD_CB_SLOW_RATE    || '50 (default)',
    },
    CARD_CORP_A_slow:    side('latency_a', 'ok_a', 'fail_a'),
    CARD_CORP_B_healthy: side('latency_b', 'ok_b', 'fail_b'),
  };

  console.log('\n========= Card Isolation Summary =========');
  console.log(JSON.stringify(summary, null, 2));
  console.log('=========================================\n');

  const file = __ENV.SUMMARY_FILE || 'isolation-summary.json';
  return {
    [file]: JSON.stringify(summary, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
