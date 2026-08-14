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
const PROB      = parseFloat(__ENV.PROB) || 0.027;
const REMAINING = parseInt(__ENV.REMAINING) || 10_000_000;

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
    injectServerFailures(card, 'capture');
  });
  injectServerFailures(FDS, 'fds_check');

  injectConnectFailure('card_auth');
  injectConnectFailure('fds_check');
  injectConnectFailure('card_approve');
  injectConnectFailure('card_capture');

  console.log(`[setup] failures injected (prob=${PROB} per rule), merchant latency reset`);
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
