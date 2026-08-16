// consistency_proof.js — 장애 하에서 "불일치 0건"을 증명하기 위한 부하.
//
// payment_chaos.js와 두 가지가 다르다.
//
// 1) **매입에는 장애를 주입하지 않는다.** 장애는 인증·FDS·승인 세 단계에만 건다.
//    장애를 하나도 안 맞는 단계를 남겨두면, 뒤에서 문제가 보일 때 그게 주입한 장애 탓인지
//    우리 쪽 문제인지 가를 수 있다. 대조군 없는 측정은 원인을 못 짚는다.
//
// 2) **가맹점이 UNKNOWN을 버리지 않는다.** payment_chaos.js는 UNKNOWN을 받으면 그 결제를
//    포기하고 다음 iteration으로 간다. 그러면 승인·매입 단계에는 "복구를 거쳐 들어온 건"이
//    구조적으로 0이 되어, 복구 경로가 결제를 끝까지 밀어줬다는 사실 자체가 측정에 안 잡힌다.
//    여기서는 실제 가맹점처럼 같은 요청을 다시 보내 확정을 기다린다.
//
// 재요청이 안전한 이유는 멱등키다. 확정 전에는 키가 PROCESSING이라 409가 돌아오고,
// 조회가 확정한 뒤에는 저장된 최종 응답이 그대로 재생된다. 재요청이 카드사로 새 호출을
// 만들지 않으므로, 폴링을 아무리 해도 측정 대상 부하가 오염되지 않는다.

import http from 'k6/http';
import { sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Trend } from 'k6/metrics';

const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const CARD_A  = __ENV.CARD_A  || 'http://localhost:8084';
const CARD_B  = __ENV.CARD_B  || 'http://localhost:8085';
const FDS     = __ENV.FDS     || 'http://localhost:8083';

const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

// 단계마다 4개 룰이 각각 독립적으로 확률 판정을 한다("4종 중 하나를 랜덤 선택"이 아니다):
//   payment 클라이언트  CONNECT_FAILURE                             ← 요청이 나가기도 전
//   card / fds 서버     TIMEOUT_BEFORE / TIMEOUT_AFTER / ERROR_500
// prob=0.027이면 단계별 실효 장애율 ≈ 1-0.973^4 = 10.4%.
const PROB      = __ENV.PROB !== undefined ? parseFloat(__ENV.PROB) : 0.027;
const REMAINING = parseInt(__ENV.REMAINING) || 10_000_000;

const TPS      = parseInt(__ENV.TPS)      || 150;
const DURATION = __ENV.DURATION           || '2m';

// 가맹점 수. VU마다 다른 가맹점을 쓰면 150 TPS에서 1,200곳이 생기는데, 그건 부하 생성기의
// 동시성이지 가맹점 수가 아니다. 소수의 가맹점에 결제가 몰리는 쪽이 실제에 가깝고,
// 그래야 MERCHANT_PENDING 같은 핫 계정에 경합이 실제로 걸린다 — 잔액을 직접 UPDATE하지 않고
// 원장에 append만 하는 설계가 값어치를 증명해야 하는 지점이 거기다.
const MERCHANTS = parseInt(__ENV.MERCHANTS) || 20;

// 재개 폴링 예산. UNKNOWN은 대개 첫 조회에서 풀리지만(조회 백오프 사다리가 3s에서 시작한다),
// 조회 자체가 CONNECT_FAILURE를 맞으면 3s → 10s → 30s로 밀린다.
// 여기서 포기해도 결제가 죽는 게 아니다 — 스케줄러가 계속 확정시키고, 그 건은
// "가맹점이 기다리다 만 결제"로 집계된다. 정합성 판정은 그것과 무관하게 성립해야 한다.
const RESUME_TIMEOUT_MS  = parseInt(__ENV.RESUME_TIMEOUT_MS)  || 20_000;
const RESUME_INTERVAL_MS = parseInt(__ENV.RESUME_INTERVAL_MS) || 1_000;

// 필요한 VU 수 = 도착률 × iteration 소요시간. 재개 폴링이 붙으면 iteration이 수 초로 늘어난다.
// VU가 모자라 iteration이 drop되면 실제 부하가 설정보다 낮아지고, 그러면 이 런이
// "150 TPS에서의 결과"라는 전제 자체가 깨진다.
const PRE_VUS = parseInt(__ENV.PRE_VUS) || Math.max(800, TPS * 8);
const MAX_VUS = parseInt(__ENV.MAX_VUS) || Math.max(4000, TPS * 40);

// ── 메트릭 ──────────────────────────────────────────────────
// 가맹점이 본 것만 센다. 시스템 내부의 최종 진실은 /admin/metrics/funnel이 DB에서 뽑는다.
// 두 시점이 다르기 때문에 굳이 나눈다 — k6는 부하 중에, funnel은 수렴 후에 본다.
const STAGES = ['request', 'approve', 'capture'];
const M = {};
STAGES.forEach(stage => {
  M[stage] = {
    directOk:  new Counter(`${stage}_direct_ok`),   // 동기 호출 한 번에 확정 성공
    resumedOk: new Counter(`${stage}_resumed_ok`),  // UNKNOWN을 받고 재개해서 성공 확정
    failed:    new Counter(`${stage}_failed`),      // 확정 실패
    abandoned: new Counter(`${stage}_abandoned`),   // 재개 예산을 다 쓰고도 미확정
    httpError: new Counter(`${stage}_http_error`),  // payment가 200/409를 못 준 경우
    resumeMs:  new Trend(`${stage}_resume_ms`, true), // UNKNOWN → 확정까지 가맹점이 기다린 시간
  };
});
const started   = new Counter('payments_started');
const completed = new Counter('payments_completed'); // 매입까지 동기로 완주

// ── 장애 주입 ────────────────────────────────────────────────
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
    proof: {
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
  // 인증·승인만. 매입(card의 'capture', payment의 'card_capture')은 대조군으로 남긴다.
  [CARD_A, CARD_B].forEach(card => {
    injectServerFailures(card, 'auth');
    injectServerFailures(card, 'approve');
  });
  injectServerFailures(FDS, 'fds_check');

  injectConnectFailure('card_auth');
  injectConnectFailure('fds_check');
  injectConnectFailure('card_approve');

  const effective = (100 * (1 - Math.pow(1 - PROB, 4))).toFixed(1);
  console.log(`[setup] prob=${PROB} per rule x 4 rules -> ~${effective}% per stage on auth/fds/approve`);
  console.log(`[setup] capture is the control — no failure injected`);
}

export function teardown() {
  [CARD_A, CARD_B, FDS, PAYMENT].forEach(url => http.del(`${url}/admin/failure`));
  console.log('[teardown] cleared all failures');
}

// ── 메인 루프 ────────────────────────────────────────────────
export default function () {
  const merchantId = `proof-${String(__VU % MERCHANTS).padStart(3, '0')}`;
  const orderId    = `proof-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  const amount     = 10000;
  const company    = (__VU % 2 === 1) ? 'CARD_CORP_A' : 'CARD_CORP_B';
  const body       = JSON.stringify({ orderId, merchantId, amount, cardCompany: company });

  started.add(1);

  // ── 1) 인증 + FDS ─────────────────────────────────────────
  const auth = call('request', () => http.post(`${PAYMENT}/v1/payment`, body, reqOpts()));
  if (auth.outcome !== 'FDS_PASSED') return;

  const paymentKey = auth.body.paymentKey;
  if (!paymentKey) return;

  // ── 2) 승인 ───────────────────────────────────────────────
  const approve = call('approve', () =>
      http.post(`${PAYMENT}/v1/payment/${paymentKey}/approve`, null, reqOpts()));
  if (approve.outcome !== 'APPROVED') return;

  // ── 3) 매입 ───────────────────────────────────────────────
  // 매입 상태는 PaymentIntent가 아니라 CAPTURE 트랜잭션에 있다.
  const capture = call('capture', () =>
      http.post(`${PAYMENT}/v1/payment/${paymentKey}/capture`, null, reqOpts()));
  if (capture.outcome === 'SUCCEEDED') completed.add(1);
}

function reqOpts() {
  return { ...JSON_HDR, timeout: '15s' };
}

/**
 * 한 단계를 호출하고, UNKNOWN이면 확정될 때까지 같은 요청을 다시 보낸다.
 *
 * <p>재요청은 새 시도가 아니라 <b>같은 시도의 결과를 다시 묻는 것</b>이다. 멱등키가 그렇게
 * 만들어 준다 — 미확정이면 409, 확정됐으면 저장된 응답의 재생. 그래서 이 폴링은 카드사로
 * 나가는 호출을 늘리지 않고, 측정 중인 부하를 오염시키지 않는다.
 */
function call(stage, send) {
  const metrics = M[stage];
  const first = send();

  if (first.status !== 200) {
    metrics.httpError.add(1);
    return { outcome: null, body: {} };
  }

  const firstOutcome = outcomeOf(stage, first);
  if (!isUnknown(firstOutcome)) {
    if (isOk(stage, firstOutcome)) metrics.directOk.add(1);
    else metrics.failed.add(1);
    return { outcome: firstOutcome, body: first.json() };
  }

  // 여기부터 재개. 가맹점이 기다린 시간을 재는 구간이다.
  const startedAt = Date.now();
  while (Date.now() - startedAt < RESUME_TIMEOUT_MS) {
    // 지터를 주지 않으면 같은 순간 UNKNOWN이 된 VU들이 한 덩어리로 몰려 재요청한다.
    sleep((RESUME_INTERVAL_MS * (0.5 + Math.random())) / 1000);

    const retry = send();

    // 409는 "아직 확정 전"이라는 정상 응답이다. 계속 기다린다.
    if (retry.status === 409) continue;
    if (retry.status !== 200) {
      metrics.httpError.add(1);
      return { outcome: null, body: {} };
    }

    const outcome = outcomeOf(stage, retry);
    if (isUnknown(outcome)) continue;

    metrics.resumeMs.add(Date.now() - startedAt);
    if (isOk(stage, outcome)) metrics.resumedOk.add(1);
    else metrics.failed.add(1);
    return { outcome, body: retry.json() };
  }

  metrics.abandoned.add(1);
  return { outcome: null, body: {} };
}

// 매입만 응답 필드가 다르다 — 매입은 PaymentIntent 상태를 바꾸지 않기 때문이다.
function outcomeOf(stage, response) {
  return response.json(stage === 'capture' ? 'captureStatus' : 'status');
}

function isUnknown(outcome) {
  return typeof outcome === 'string' && outcome.startsWith('UNKNOWN');
}

function isOk(stage, outcome) {
  if (stage === 'request') return outcome === 'FDS_PASSED';
  if (stage === 'approve') return outcome === 'APPROVED';
  return outcome === 'SUCCEEDED';
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = name => (m[name] ? m[name].values.count : 0);
  const trend = name => (m[name] ? {
    count: m[name].values.count,
    p50: Math.round(m[name].values.med),
    p95: Math.round(m[name].values['p(95)']),
    max: Math.round(m[name].values.max),
  } : null);

  const stages = {};
  STAGES.forEach(stage => {
    const directOk  = count(`${stage}_direct_ok`);
    const resumedOk = count(`${stage}_resumed_ok`);
    stages[stage] = {
      // 이 단계까지 도달해 응답을 받아낸 건. 앞 단계에서 죽은 결제는 여기 안 들어온다.
      reached:   directOk + resumedOk + count(`${stage}_failed`)
                 + count(`${stage}_abandoned`) + count(`${stage}_http_error`),
      ok:        directOk + resumedOk,
      directOk:  directOk,
      resumedOk: resumedOk,
      failed:    count(`${stage}_failed`),
      abandoned: count(`${stage}_abandoned`),
      httpError: count(`${stage}_http_error`),
      resumeWaitMs: trend(`${stage}_resume_ms`),
    };
  });

  const startedCount = count('payments_started');
  const droppedIterations = count('dropped_iterations');
  const httpErrorTotal = STAGES.reduce((sum, s) => sum + count(`${s}_http_error`), 0);

  const summary = {
    params: {
      tps: TPS,
      duration: DURATION,
      prob: PROB,
      failureStages: ['auth', 'fds', 'approve'],
      controlStage: 'capture',
      effectiveFailureRatePerStagePct: Math.round(1000 * (1 - Math.pow(1 - PROB, 4))) / 10,
      resumeTimeoutMs: RESUME_TIMEOUT_MS,
    },
    merchantView: {
      started: startedCount,
      completed: count('payments_completed'),
      completedPct: startedCount
          ? Math.round(1000 * count('payments_completed') / startedCount) / 10 : null,
      stages,
    },
    // 부하 생성기가 먼저 무너지면 이 런은 "150 TPS에서의 결과"가 아니다.
    // 판정 근거를 결과 파일 안에 박아둬야 나중에 그 숫자를 다시 믿지 않는다.
    validity: {
      loadGeneratorSaturated: droppedIterations > 0
          || (startedCount ? httpErrorTotal / startedCount > 0.005 : false),
      droppedIterations,
      httpErrorTotal,
      httpErrorPct: startedCount ? Math.round(1000 * httpErrorTotal / startedCount) / 10 : null,
      preAllocatedVUs: PRE_VUS,
      maxVusUsed: m.vus_max ? m.vus_max.values.max : null,
    },
  };

  const out = { stdout: JSON.stringify(summary.merchantView, null, 2) + '\n' };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(summary, null, 2);
  return out;
}
