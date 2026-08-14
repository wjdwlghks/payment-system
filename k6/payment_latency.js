import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';

// ── 목적 ─────────────────────────────────────────────────────
// UNKNOWN이 발생한 결제가 "결제 완료"(= APPROVED)에 도달하기까지 걸리는 시간을 잰다.
// 지연은 여기서 재지 않는다 — 가맹점 서버가 요청 발신부터 APPROVED 인지까지를 자체 계측하고,
// 결과는 GET merchant/admin/latency 로 꺼낸다.
//
// payment_chaos.js 와 다른 점 두 가지:
//
//  1) payment가 아니라 merchant를 때린다. 그리고 결제 요청 하나만 보낸다 —
//     승인·매입은 가맹점이 스스로 이어간다(동기 응답이면 즉시, UNKNOWN이면 웹훅을 받고).
//     기존 스크립트는 UNKNOWN 응답을 받으면 return으로 그 결제를 버렸기 때문에
//     UNKNOWN 건이 결제 완료에 도달한 적이 없었고, 따라서 잴 지연 자체가 없었다.
//
//  2) run-now를 부르지 않는다. /admin/scheduler/run-now로 스케줄러를 수동으로 때리면
//     측정되는 게 "복구에 걸린 시간"이 아니라 "내가 얼마나 자주 때렸나"가 된다.
//     스케줄러는 자기 주기(inquiry 10s)대로 돌게 두고, 부하 종료 후 수렴을 그냥 기다린다.
//
// 사용법:
//   k6 run k6/payment_latency.js
//   PROB=0.05 TPS=20 DURATION=2m k6 run k6/payment_latency.js

const requested   = new Counter('requested');       // merchant가 접수한 결제
const acceptedSync = new Counter('accepted_sync');  // 동기 응답이 FDS_PASSED (정상 경로)
const acceptedUnknown = new Counter('accepted_unknown'); // 동기 응답이 UNKNOWN_* (웹훅 경로)
const rejected    = new Counter('rejected');        // AUTH_FAILED / FDS_FAILED
const httpError   = new Counter('http_error');

const MERCHANT = __ENV.MERCHANT || 'http://localhost:8081';
const PAYMENT  = __ENV.PAYMENT  || 'http://localhost:8082';
const CARD_A   = __ENV.CARD_A   || 'http://localhost:8084';
const CARD_B   = __ENV.CARD_B   || 'http://localhost:8085';
const FDS      = __ENV.FDS      || 'http://localhost:8083';

const JSON_HDR  = { headers: { 'Content-Type': 'application/json' } };

// PROB는 "지점당 장애율"이 아니라 <b>룰 하나가 발동할 확률</b>이다.
// FailureRegistry.consumeByAlias가 한 alias의 룰들을 전부 순회하며 각자 독립적으로 판정하고
// 통과한 것 중 첫 번째를 적용하기 때문이다. 지점마다 룰이 4개(서버 3종 + 클라이언트 CONNECT_FAILURE)
// 걸리므로 실효 장애율 ≈ 1 - (1-PROB)^4. PROB=0.027이면 지점당 약 10.4%.
const PROB      = parseFloat(__ENV.PROB) || 0.027;
const REMAINING = parseInt(__ENV.REMAINING) || 10_000_000;

// 매입 장애는 기본으로 끈다. 사용자 지연은 승인까지라 매입 장애가 측정값에 안 들어오고,
// 매입에 UNKNOWN을 섞으면 매입 지연 분포만 흐려진다. 나중에 매입 경로를 따로 볼 때 켠다.
const CAPTURE_FAILURE = (__ENV.CAPTURE_FAILURE || 'false') === 'true';

const TPS      = parseInt(__ENV.TPS)      || 20;
const DURATION = __ENV.DURATION           || '2m';
const PRE_VUS  = parseInt(__ENV.PRE_VUS)  || Math.max(50, TPS);
const MAX_VUS  = parseInt(__ENV.MAX_VUS)  || Math.max(1000, TPS * 30);

function injectServerFailures(baseUrl, endpoint) {
  ['TIMEOUT_BEFORE_PROCESS', 'TIMEOUT_AFTER_PROCESS', 'ERROR_500'].forEach(failure => {
    http.post(`${baseUrl}/admin/failure`, JSON.stringify({
      endpoint, failure, remaining: REMAINING, triggerProbability: PROB,
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

export const options = {
  scenarios: {
    latency: {
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
  // 이전 런의 표본이 섞이지 않도록 가맹점 계측을 비운다
  http.post(`${MERCHANT}/admin/latency/reset`);

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

  const stages = CAPTURE_FAILURE ? 'auth/fds/approve/capture' : 'auth/fds/approve';
  const effective = (100 * (1 - Math.pow(1 - PROB, 4))).toFixed(1);
  console.log(`[setup] failures on ${stages} (prob=${PROB} per rule, 4 rules -> ~${effective}% per stage)`);
  console.log('[setup] merchant latency reset');
}

export function teardown() {
  http.del(`${CARD_A}/admin/failure`);
  http.del(`${CARD_B}/admin/failure`);
  http.del(`${FDS}/admin/failure`);
  http.del(`${PAYMENT}/admin/failure`);
  console.log('[teardown] cleared all failures');
  console.log('[teardown] 수렴을 기다린 뒤 아래를 확인:');
  console.log(`[teardown]   curl ${PAYMENT}/admin/convergence`);
  console.log(`[teardown]   curl ${MERCHANT}/admin/latency`);
}

export default function () {
  const merchantId = `lat-${String(__VU).padStart(3, '0')}`;
  const orderId    = `lat-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const company    = (__VU % 2 === 1) ? 'CARD_CORP_A' : 'CARD_CORP_B';

  // 가맹점에만 요청한다. 승인·매입은 가맹점이 이어간다.
  const res = http.post(
    `${MERCHANT}/api/payments`,
    JSON.stringify({ orderId, merchantId, amount: 10000, cardCompany: company }),
    { ...JSON_HDR, timeout: '30s' }
  );

  requested.add(1);

  if (!check(res, { 'request 200': r => r.status === 200 })) {
    httpError.add(1);
    return;
  }

  const status = res.json('status');
  if (status === 'FDS_PASSED')            acceptedSync.add(1);
  else if (String(status).startsWith('UNKNOWN')) acceptedUnknown.add(1);
  else                                    rejected.add(1);
}

// ── 처리량 요약 ───────────────────────────────────────────────
// 세 가지를 구분해서 본다:
//   offered   — 목표 발생률(TPS 설정값)
//   achieved  — 실제로 merchant가 접수한 초당 건수. dropped_iterations가 크면 여기서 벌어진다.
//   완료율은 k6가 모르므로 merchant/admin/latency 쪽 completed로 따로 계산한다.
export function handleSummary(data) {
  const m = data.metrics;
  const val = (name, field) => (m[name] && m[name].values[field] !== undefined ? m[name].values[field] : 0);
  const durationSec = data.state.testRunDurationMs / 1000;

  const summary = {
    durationSec: Number(durationSec.toFixed(1)),
    offeredRps: TPS,
    achievedRps: Number(val('requested', 'rate').toFixed(1)),
    requested: val('requested', 'count'),
    acceptedSync: val('accepted_sync', 'count'),
    acceptedUnknown: val('accepted_unknown', 'count'),
    rejected: val('rejected', 'count'),
    httpError: val('http_error', 'count'),
    droppedIterations: val('dropped_iterations', 'count'),
    merchantRequestMs: {
      p50: Math.round(val('http_req_duration', 'med')),
      p95: Math.round(val('http_req_duration', 'p(95)')),
      max: Math.round(val('http_req_duration', 'max')),
    },
  };

  const out = {};
  if (__ENV.SUMMARY_OUT) {
    out[__ENV.SUMMARY_OUT] = JSON.stringify(summary, null, 2);
  }
  out.stdout = '\n' + JSON.stringify(summary, null, 2) + '\n';
  return out;
}
