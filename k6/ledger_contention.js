// ledger_contention.js — 매입 기표의 락 대기 병목을 재기 위한 부하.
//
// 재려는 것은 **DB 신호 하나**다. 그래서 그 신호를 흐리는 것들을 전부 껐다.
//
//   장애 주입 없음   UNKNOWN이 섞이면 매입 건수와 지연이 흔들려 락 대기가 묻힌다.
//   카드사 한 곳     두 곳에 나누면 카드사당 실부하가 절반이라 같은 TPS라도 매입 압력이 달라진다.
//   Bulkhead 해제    한 곳에 전부 몰리므로 퍼밋 80이 먼저 걸린다. 그러면 DB가 아니라
//                    Bulkhead 거절을 재게 된다. (러너가 환경변수로 사실상 무한대로 준다.)
//
// 병목은 매입에만 생기지만 측정은 세 단계를 다 본다 — 매입이 커넥션 풀을 물고 늘어지면
// 인증·승인까지 같이 느려지는지가 "핫 로우 하나가 서비스를 어디까지 끌어내리나"의 답이다.

import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Trend } from 'k6/metrics';

const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

const TPS      = parseInt(__ENV.TPS) || 150;
const DURATION = __ENV.DURATION      || '1m';

// 가맹점 수. MERCHANT_PENDING은 가맹점마다 다른 행이라 여기서 경합이 갈리지 않는다.
// 이 측정의 표적은 전 결제가 공유하는 CARD_NETWORK_RECEIVABLE / FEE_REVENUE 쪽이다.
const MERCHANTS = parseInt(__ENV.MERCHANTS) || 20;

// 인라인 모드가 무너지면 매입이 초 단위로 늘어져 VU가 폭증한다. 넉넉히 깔아둔다.
// 그래도 drop이 나면 그건 설정 실수가 아니라 그 모드가 부하를 못 받아냈다는 결과다.
const PRE_VUS = parseInt(__ENV.PRE_VUS) || Math.max(500, TPS * 6);
const MAX_VUS = parseInt(__ENV.MAX_VUS) || Math.max(3000, TPS * 30);

// 처리율의 분모는 부하 구간이어야 한다. k6가 붙여주는 rate는 setup/teardown까지 포함한
// 전체 런 시간으로 나눈 값이라, 도착률을 고정해 놓고도 낮게 읽힌다.
const LOAD_WINDOW_SEC = parseDuration(DURATION);

function parseDuration(text) {
  const matched = String(text).match(/(\d+(?:\.\d+)?)(ms|s|m|h)/g) || [];
  const unit = { ms: 0.001, s: 1, m: 60, h: 3600 };
  return matched.reduce((total, part) => {
    const [, value, suffix] = part.match(/(\d+(?:\.\d+)?)(ms|s|m|h)/);
    return total + parseFloat(value) * unit[suffix];
  }, 0) || 1;
}

const PHASES = ['request', 'approve', 'capture'];
const latency = {};
PHASES.forEach(phase => { latency[phase] = new Trend(`${phase}_ms`, true); });

const started   = new Counter('payments_started');
const completed = new Counter('payments_completed');
const failed    = new Counter('payments_failed');     // 4xx/확정 실패
const httpError = new Counter('payments_http_error'); // payment가 200을 못 준 경우

export const options = {
  summaryTrendStats: ['count', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg'],
  scenarios: {
    contention: {
      executor: 'constant-arrival-rate',
      rate: TPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
};

export default function () {
  const merchantId = `ledger-${String(__VU % MERCHANTS).padStart(3, '0')}`;
  const orderId    = `ledger-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;

  started.add(1);

  const auth = call('request', () => http.post(`${PAYMENT}/v1/payment`, JSON.stringify({
    orderId, merchantId, amount: 10000, cardCompany: 'CARD_CORP_A',
  }), reqOpts()));
  if (auth.status !== 'FDS_PASSED' || !auth.body.paymentKey) return;

  const paymentKey = auth.body.paymentKey;

  const approve = call('approve', () =>
      http.post(`${PAYMENT}/v1/payment/${paymentKey}/approve`, null, reqOpts()));
  if (approve.status !== 'APPROVED') return;

  // 매입 상태는 PaymentIntent가 아니라 CAPTURE 트랜잭션에 있다.
  const capture = call('capture', () =>
      http.post(`${PAYMENT}/v1/payment/${paymentKey}/capture`, null, reqOpts()), 'captureStatus');
  if (capture.status === 'SUCCEEDED') completed.add(1);
}

// 락 대기가 길어지면 응답도 그만큼 늦다. 30s로 잡아 "느린 것"과 "끊긴 것"을 가른다 —
// 15s로 끊으면 인라인 모드의 꼬리가 통째로 httpError가 되어 지연 분포가 사라진다.
function reqOpts() {
  return { ...JSON_HDR, timeout: '30s' };
}

function call(phase, send, field = 'status') {
  const response = send();
  latency[phase].add(response.timings.duration);

  if (response.status !== 200) {
    httpError.add(1);
    return { status: null, body: {} };
  }

  const body = response.json();
  const status = body[field];
  if (!isOk(phase, status)) failed.add(1);
  return { status, body };
}

function isOk(phase, status) {
  if (phase === 'request') return status === 'FDS_PASSED';
  if (phase === 'approve') return status === 'APPROVED';
  return status === 'SUCCEEDED';
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = name => (m[name] ? m[name].values.count : 0);
  const stats = name => {
    const v = m[name] && m[name].values;
    if (!v) return null;
    return {
      count: v.count,
      p50: Math.round(v.med),
      p95: Math.round(v['p(95)']),
      p99: Math.round(v['p(99)']),
      max: Math.round(v.max),
      avg: Math.round(v.avg),
    };
  };

  const startedCount   = count('payments_started');
  const completedCount = count('payments_completed');
  const dropped        = count('dropped_iterations');
  const capture        = stats('capture_ms');

  const summary = {
    params: {
      tps: TPS,
      duration: DURATION,
      loadWindowSec: LOAD_WINDOW_SEC,
      merchants: MERCHANTS,
      cardCompany: 'CARD_CORP_A',
      chaos: 'none',
      ledgerBalanceMode: __ENV.LEDGER_BALANCE_MODE || 'SNAPSHOT (default)',
      bulkheadMax: __ENV.CARD_BULKHEAD_MAX || 'default',
    },
    // 이 측정의 헤드라인 두 개. 나머지 둘(lock_waits/total_wait)은 러너가 MySQL에서 뽑는다.
    headline: {
      offeredRps:   TPS,
      throughput:   Math.round(10 * completedCount / LOAD_WINDOW_SEC) / 10, // 완주 결제/초
      captureP95Ms: capture ? capture.p95 : null,
      completedPct: startedCount ? Math.round(1000 * completedCount / startedCount) / 10 : null,
    },
    throughput: {
      started:   startedCount,
      completed: completedCount,
      failed:    count('payments_failed'),
      httpError: count('payments_http_error'),
      startedRps:   Math.round(10 * startedCount / LOAD_WINDOW_SEC) / 10,
      completedRps: Math.round(10 * completedCount / LOAD_WINDOW_SEC) / 10,
    },
    latency: {
      request: stats('request_ms'),
      approve: stats('approve_ms'),
      capture: capture,
    },
    // 부하 생성기가 먼저 무너졌는지. drop이 나면 실제 부하가 설정보다 낮았다는 뜻이라
    // "그 모드가 이만큼 받아냈다"가 아니라 "재지 못했다"가 된다 — 다만 인라인 모드에서는
    // 매입이 늘어져서 나는 drop이 곧 결과이기도 하므로, 판단은 결과를 읽는 쪽에 맡긴다.
    validity: {
      droppedIterations: dropped,
      httpError: count('payments_http_error'),
      preAllocatedVUs: PRE_VUS,
      maxVusUsed: m.vus_max ? m.vus_max.values.max : null,
    },
  };

  const out = { stdout: JSON.stringify(summary.headline, null, 2) + '\n' };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(summary, null, 2);
  return out;
}
