import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Trend, Counter } from 'k6/metrics';

// ── 목적 ─────────────────────────────────────────────────────
// "요청을 보내고 응답을 받기까지" 만 잰다. 성공이든 UNKNOWN이든 응답이 오면 그 자리에서 끝낸다.
//
// payment_latency.js 와 재는 대상이 다르다:
//   payment_latency.js  요청 → APPROVED 인지        (사용자 체감. 복구 경로 포함)
//   이 스크립트          요청 → 응답 수신            (동기 경로만. 복구 경로 제외)
//
// 왜 나눠야 하나 — 사용자 체감 지연에는 소켓 타임아웃(3s)과 첫 조회 예약 지연(3s) 같은
// **설정 상수**가 대부분을 차지해서, 그 안에 섞인 우리 큐잉이 안 보인다. 동기 응답만 떼어내면
// 기대값이 명확해지고, **기대값을 넘는 만큼이 곧 우리 쪽 병목**이 된다.
//
// merchant가 아니라 payment를 직접 때린다. merchant를 거치면 merchant의 Tomcat·워커·커넥션 풀이
// 섞여서 초과분이 누구 것인지 다시 모호해진다.
//
// ── 응답 status별 기대 시간 ──────────────────────────────────
// POST /v1/payment 는 인증과 FDS를 한 호출 안에서 순차로 처리하므로,
// 어느 단계에서 멈췄는지는 응답 status가 알려준다.
//
//   FDS_PASSED    ≈   40ms   카드사 왕복 + FDS 왕복
//   UNKNOWN_AUTH           인증에서 멈춤 — 주입 종류에 따라 셋으로 갈린다:
//                   ERROR_500        ≈    5ms  (즉시 5xx)
//                   CONNECT_FAILURE  ≈ 1,000ms (인터셉터가 1초 자고 던짐)
//                   TIMEOUT_*        ≈ 3,000ms (소켓 타임아웃)
//   UNKNOWN_FDS            인증은 통과, FDS에서 멈춤 → 위 값 + 인증 왕복(~20ms)
//
// 따라서 UNKNOWN_* 의 **p95는 3,000ms 근처에 붙어야 정상**이고,
// 그보다 큰 만큼이 Tomcat 대기·커넥션 풀 대기·DB 경합 같은 우리 쪽 큐잉이다.

const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const CARD_A  = __ENV.CARD_A  || 'http://localhost:8084';
const CARD_B  = __ENV.CARD_B  || 'http://localhost:8085';
const FDS     = __ENV.FDS     || 'http://localhost:8083';

const JSON_HDR  = { headers: { 'Content-Type': 'application/json' } };
const PROB      = __ENV.PROB !== undefined ? parseFloat(__ENV.PROB) : 0.027;
const REMAINING = parseInt(__ENV.REMAINING) || 10_000_000;

// 매입 장애는 기본으로 끈다. 장애를 하나도 안 맞는 단계를 하나 남겨두면,
// "우리 쪽 큐잉" 신호를 오염 없이 읽을 수 있는 대조군이 생긴다.
const CAPTURE_FAILURE = (__ENV.CAPTURE_FAILURE || 'false') === 'true';

const TPS      = parseInt(__ENV.TPS)      || 150;
const DURATION = __ENV.DURATION           || '2m';
// 3~5초짜리 응답이 몰리면 순간 동시성이 크게 튄다. 런 중에 VU를 증설하면
// 초기화가 늦어 iteration이 drop되므로, 처음부터 넉넉히 깔아둔다.
const PRE_VUS  = parseInt(__ENV.PRE_VUS)  || Math.max(200, TPS * 4);
const MAX_VUS  = parseInt(__ENV.MAX_VUS)  || Math.max(2000, TPS * 30);

// ── 메트릭: (단계 × 응답 status) 별로 따로 모은다 ──────────────
// k6 메트릭은 init 단계에서만 만들 수 있어 조합을 전부 나열한다.
const T = {
  // POST /v1/payment  (인증 + FDS)
  request_FDS_PASSED:    new Trend('request_FDS_PASSED_ms', true),
  request_UNKNOWN_AUTH:  new Trend('request_UNKNOWN_AUTH_ms', true),
  request_UNKNOWN_FDS:   new Trend('request_UNKNOWN_FDS_ms', true),
  request_AUTH_FAILED:   new Trend('request_AUTH_FAILED_ms', true),
  request_FDS_FAILED:    new Trend('request_FDS_FAILED_ms', true),
  request_HTTP_ERROR:    new Trend('request_HTTP_ERROR_ms', true),

  // POST /v1/payment/{key}/approve
  approve_APPROVED:        new Trend('approve_APPROVED_ms', true),
  approve_UNKNOWN_APPROVE: new Trend('approve_UNKNOWN_APPROVE_ms', true),
  approve_APPROVE_FAILED:  new Trend('approve_APPROVE_FAILED_ms', true),
  approve_HTTP_ERROR:      new Trend('approve_HTTP_ERROR_ms', true),

  // POST /v1/payment/{key}/capture  (기본적으로 장애 미주입 = 대조군)
  capture_SUCCEEDED:  new Trend('capture_SUCCEEDED_ms', true),
  capture_UNKNOWN:    new Trend('capture_UNKNOWN_ms', true),
  capture_FAIL:       new Trend('capture_FAIL_ms', true),
  capture_HTTP_ERROR: new Trend('capture_HTTP_ERROR_ms', true),
};

const started = new Counter('payments_started');

function record(phase, outcome, ms) {
  const trend = T[`${phase}_${outcome}`];
  if (trend) {
    trend.add(ms);
  } else {
    // 예상 못 한 status가 오면 조용히 삼키지 말고 드러낸다.
    console.warn(`[record] unmapped outcome phase=${phase} outcome=${outcome}`);
  }
}

function injectServerFailures(baseUrl, endpoint) {
  ['TIMEOUT_BEFORE_PROCESS', 'TIMEOUT_AFTER_PROCESS', 'ERROR_500'].forEach(failure => {
    http.post(`${baseUrl}/admin/failure`, JSON.stringify({
      endpoint, failure, remaining: REMAINING, triggerProbability: PROB,
    }), JSON_HDR);
  });
}

function injectConnectFailure(paymentAlias) {
  http.post(`${PAYMENT}/admin/failure`, JSON.stringify({
    endpoint: paymentAlias, failure: 'CONNECT_FAILURE',
    remaining: REMAINING, triggerProbability: PROB,
  }), JSON_HDR);
}

export const options = {
  summaryTrendStats: ['count', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'avg'],
  scenarios: {
    sync: {
      executor: 'constant-arrival-rate',
      rate: TPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
};

export function setup() {
  if (PROB <= 0) {
    console.log('[setup] PROB=0 — 장애 주입 없음 (baseline)');
    return;
  }

  [CARD_A, CARD_B].forEach(card => {
    injectServerFailures(card, 'auth');
    injectServerFailures(card, 'approve');
    if (CAPTURE_FAILURE) injectServerFailures(card, 'capture');
  });
  injectServerFailures(FDS, 'fds_check');

  injectConnectFailure('card_auth');
  injectConnectFailure('fds_check');
  injectConnectFailure('card_approve');
  if (CAPTURE_FAILURE) injectConnectFailure('card_capture');

  const effective = (100 * (1 - Math.pow(1 - PROB, 4))).toFixed(1);
  console.log(`[setup] prob=${PROB} per rule, 4 rules -> ~${effective}% per stage`);
}

export function teardown() {
  [CARD_A, CARD_B, FDS, PAYMENT].forEach(url => http.del(`${url}/admin/failure`));
  console.log('[teardown] cleared all failures');
}

export default function () {
  const merchantId = `sync-${String(__VU).padStart(3, '0')}`;
  const orderId    = `sync-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const company    = (__VU % 2 === 1) ? 'CARD_CORP_A' : 'CARD_CORP_B';

  started.add(1);

  // ── 1) 인증 + FDS ─────────────────────────────────────────
  const res = http.post(
    `${PAYMENT}/v1/payment`,
    JSON.stringify({ orderId, merchantId, amount: 10000, cardCompany: company }),
    { ...JSON_HDR, timeout: '15s' }
  );

  if (res.status !== 200) {
    record('request', 'HTTP_ERROR', res.timings.duration);
    return;
  }

  const status     = res.json('status');
  const paymentKey = res.json('paymentKey');
  record('request', status, res.timings.duration);

  // UNKNOWN을 받으면 여기서 측정 완료 — 복구는 스케줄러가 알아서 한다.
  if (status !== 'FDS_PASSED' || !paymentKey) {
    return;
  }

  // ── 2) 승인 ───────────────────────────────────────────────
  const approveRes = http.post(
    `${PAYMENT}/v1/payment/${paymentKey}/approve`, null,
    { ...JSON_HDR, timeout: '15s' }
  );

  if (approveRes.status !== 200) {
    record('approve', 'HTTP_ERROR', approveRes.timings.duration);
    return;
  }

  const approveStatus = approveRes.json('status');
  record('approve', approveStatus, approveRes.timings.duration);

  if (approveStatus !== 'APPROVED') {
    return;
  }

  // ── 3) 매입 ───────────────────────────────────────────────
  // 매입 상태는 PaymentIntent가 아니라 CAPTURE 트랜잭션에 있다.
  const captureRes = http.post(
    `${PAYMENT}/v1/payment/${paymentKey}/capture`, null,
    { ...JSON_HDR, timeout: '15s' }
  );

  if (captureRes.status !== 200) {
    record('capture', 'HTTP_ERROR', captureRes.timings.duration);
    return;
  }

  record('capture', captureRes.json('captureStatus'), captureRes.timings.duration);
}

export function handleSummary(data) {
  const m = data.metrics;

  const stats = name => {
    const v = m[name] && m[name].values;
    if (!v || !v.count) return null;
    return {
      count: v.count,
      min:   Math.round(v.min),
      p50:   Math.round(v.med),
      p90:   Math.round(v['p(90)']),
      p95:   Math.round(v['p(95)']),
      p99:   Math.round(v['p(99)']),
      max:   Math.round(v.max),
    };
  };

  const out = {};
  Object.keys(T).forEach(key => {
    const s = stats(`${key}_ms`);
    if (s) out[key] = s;
  });

  const iter = m.iterations ? m.iterations.values : {};
  const summary = {
    params: { prob: PROB, tps: TPS, duration: DURATION },
    throughput: {
      started:           m.payments_started ? m.payments_started.values.count : 0,
      achievedRps:       iter.rate ? Math.round(iter.rate * 10) / 10 : null,
      droppedIterations: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
      preAllocatedVUs:   PRE_VUS,
      maxVusUsed:        m.vus_max ? m.vus_max.values.max : null,
    },
    // 소켓 타임아웃(3s)이 동기 응답의 이론적 상한이다. UNKNOWN_* 의 p95가 이 값을
    // 크게 넘으면 초과분은 카드사가 아니라 우리 쪽 대기다.
    socketTimeoutMs: 3000,
    syncLatency: out,
  };

  const path = __ENV.SUMMARY_OUT || 'payment-sync-latency-summary.json';
  return {
    [path]: JSON.stringify(summary, null, 2),
    stdout: JSON.stringify(summary, null, 2) + '\n',
  };
}
