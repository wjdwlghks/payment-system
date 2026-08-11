import http from 'k6/http';
import { sleep, check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

// ── 메트릭 ──────────────────────────────────────────────────
const authOk       = new Counter('auth_ok');         // auth 성공 (이후 fds 진행)
const authUnknown  = new Counter('auth_unknown');    // UNKNOWN_AUTH
const authFail     = new Counter('auth_fail');       // AUTH_FAILED / HTTP 에러

const fdsOk        = new Counter('fds_ok');          // FDS_READY
const fdsUnknown   = new Counter('fds_unknown');     // UNKNOWN_FDS
const fdsFail      = new Counter('fds_fail');        // FDS_FAILED

const captureOk      = new Counter('capture_ok');        // DONE
const captureUnknown = new Counter('capture_unknown');   // UNKNOWN_CAPTURE
const captureFail    = new Counter('capture_fail');      // CAPTURE_FAILED / HTTP 에러

// ── 서비스 주소 ──────────────────────────────────────────────
const PAYMENT = __ENV.PAYMENT || 'http://localhost:8082';
const CARD_A  = __ENV.CARD_A  || 'http://localhost:8084';
const CARD_B  = __ENV.CARD_B  || 'http://localhost:8085';
const FDS     = __ENV.FDS     || 'http://localhost:8083';

const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };
// 3개 룰 × triggerProbability=0.027(기본) → 단계별 장애율 ~8%, 전체 iteration 장애 경험률 ~24%
const PROB      = parseFloat(__ENV.PROB) || 0.027;
const REMAINING = 9999;

// TPS 기반 부하 — 목표 초당 iteration(결제) 수를 직접 지정
const TPS      = parseInt(__ENV.TPS)      || 30;
const DURATION = __ENV.DURATION           || '2m';
// PRE_VUS(사전 할당)는 테스트 시작 전 메모리에 바로 올라가므로 낮게 유지.
// MAX_VUS(상한)는 실제로 필요할 때만 지연 할당되므로 넉넉히 잡아도 대기중이 아니면 비용이 없음
// → TIMEOUT_BEFORE/AFTER_PROCESS(5s 지연) 꼬리로 인한 순간 동시성 폭증에 대비해 상한을 크게 잡아 drop 방지.
const PRE_VUS  = parseInt(__ENV.PRE_VUS)  || Math.max(50, TPS);
const MAX_VUS  = parseInt(__ENV.MAX_VUS)  || Math.max(1000, TPS * 30);

// ── 장애 룰 등록 헬퍼 ────────────────────────────────────────
// serverFailures: 카드/FDS 서버에 주입 (TIMEOUT_BEFORE, TIMEOUT_AFTER, ERROR_500)
// connectFailure: payment RestClient에 주입 (CONNECT_FAILURE) → UNKNOWN→inquiry 대상 (재시도 없음)
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
      executor: 'constant-arrival-rate',
      rate: TPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
    },
  },
};

// ── setup: 전 단계 장애 주입 ─────────────────────────────────
// 서버 장애(TIMEOUT_BEFORE, TIMEOUT_AFTER, ERROR_500): 카드/FDS 서버에 주입
// CONNECT_FAILURE: payment RestClient 인터셉터에 주입 → 재시도 없이 UNKNOWN→inquiry로 수렴
export function setup() {
  [CARD_A, CARD_B].forEach(card => {
    injectServerFailures(card, 'auth');
    injectServerFailures(card, 'capture');
  });
  injectServerFailures(FDS, 'fds_check');

  injectConnectFailure('card_auth');
  injectConnectFailure('fds_check');
  injectConnectFailure('card_capture');

  console.log('[setup] all failures injected on card-a/card-b/fds (prob=0.027 each, 3 types × 3 stages, refund 제외)');
}

// ── teardown: 전체 해제 ──────────────────────────────────────
export function teardown() {
  http.del(`${CARD_A}/admin/failure`);
  http.del(`${CARD_B}/admin/failure`);
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

  // VU 홀수 → CARD_CORP_A, 짝수 → CARD_CORP_B (단일 카드사 집중 회피)
  const company = (__VU % 2 === 1) ? 'CARD_CORP_A' : 'CARD_CORP_B';

  // ── 1) Auth + FDS ────────────────────────────────────────
  const authRes = http.post(
    `${PAYMENT}/v1/payment`,
    JSON.stringify({ orderId, merchantId, amount, cardCompany: company }),
    { ...JSON_HDR, ...tag, timeout: '15s' }
  );

  if (!check(authRes, { 'auth 200': r => r.status === 200 })) {
    authFail.add(1);
    return;
  }

  const authStatus = authRes.json('status');
  const paymentKey = authRes.json('paymentKey');

  // /v1/payment 응답 status로 auth/fds 단계를 분리 판정
  // auth 실패 시 fds는 실행 안 됨 (순차)
  if (authStatus === 'AUTH_FAILED')      { authFail.add(1);    return; }
  if (authStatus === 'UNKNOWN_AUTH')     { authUnknown.add(1); return; }
  if (!paymentKey)                       { authFail.add(1);    return; }

  // 여기 도달 = auth 성공
  authOk.add(1);

  if (authStatus === 'FDS_FAILED')       { fdsFail.add(1);     return; }
  if (authStatus === 'UNKNOWN_FDS')      { fdsUnknown.add(1);  return; }
  fdsOk.add(1);  // FDS_READY

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
}

// run-now는 1회 호출당 최대 300건(LIMIT 300, auth/fds/capture 공유)만 처리하므로,
// 부하 종료 시점에 쌓인 backlog를 다 비울 때까지 /admin/convergence로 확인하며 반복 호출한다.
// (10초 스케줄러를 그냥 기다리는 것보다 즉시 수렴시켜 정확한 최종 지표를 바로 얻기 위함)
function forceConvergence(maxIterations = 60) {
  for (let i = 0; i < maxIterations; i++) {
    const status = http.get(`${PAYMENT}/admin/convergence`).json();
    if (status.converged) {
      console.log(`[convergence] run-now ${i}회 호출 후 수렴 완료 (unknownTx=0, staleRequested=0, authReady=0, processingIdempotencyKeys=0)`);
      return;
    }
    http.post(`${PAYMENT}/admin/scheduler/run-now`);
  }
  console.log(`[convergence] ⚠ run-now ${maxIterations}회 호출 후에도 미수렴 — 아래 recovery 지표는 최종치가 아닐 수 있음`);
}

// ── 최종 summary ─────────────────────────────────────────────
export function handleSummary(data) {
  const m   = data.metrics;
  const get = name => (m[name] ? m[name].values.count : 0);

  const totalAuth    = get('auth_ok')    + get('auth_unknown')    + get('auth_fail');
  const totalFds     = get('fds_ok')     + get('fds_unknown')     + get('fds_fail');
  const totalCapture = get('capture_ok') + get('capture_unknown') + get('capture_fail');

  const dropped = get('dropped_iterations');
  if (dropped > 0) {
    console.log(`\n⚠ dropped_iterations=${dropped} — maxVUs(${MAX_VUS})가 부족해 목표 TPS(${TPS})를 못 채웠을 수 있음. MAX_VUS를 늘려서 재시도 권장.\n`);
  }

  forceConvergence();

  // inquiry 메트릭 조회 (재시도 제거됨 — 모든 모호한 결과는 UNKNOWN→inquiry로 복구, 위에서 강제 수렴시킨 뒤의 최종치)
  const recovery = http.get(`${PAYMENT}/admin/metrics/recovery`).json();

  console.log('\n========= Chaos Test Summary (payment only, no refund) =========');
  console.log(`Auth     total=${totalAuth}   ok=${get('auth_ok')}   unknown=${get('auth_unknown')}  fail=${get('auth_fail')}`);
  console.log(`FDS      total=${totalFds}   ok=${get('fds_ok')}   unknown=${get('fds_unknown')}  fail=${get('fds_fail')}`);
  console.log(`Capture  total=${totalCapture}  ok=${get('capture_ok')}  unknown=${get('capture_unknown')}  fail=${get('capture_fail')}`);

  console.log('\n--- Inquiry(스케줄러 재조회) Stats: unknown → success/notFound ---');
  ['auth', 'fds', 'capture'].forEach(type => {
    const s = recovery.inquiry[type];
    if (s && s.total > 0) {
      console.log(`  ${type}: total=${s.total}  success=${s.success}  notFound=${s.notFound}  failed=${s.failed}  inProgress=${s.inProgress}`);
    }
  });
  console.log('==================================================================\n');

  return {
    'payment-chaos-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
