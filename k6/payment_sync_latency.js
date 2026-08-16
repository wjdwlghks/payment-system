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

// 부하를 받을 카드사. 기본은 **한 곳**이다.
//
// 두 곳에 반씩 나눠 보내면 한 카드사가 받는 실부하가 절반이 되어, 같은 TPS라도
// Bulkhead·커넥션 풀이 얼마나 찼는지가 달라진다. 격리 장치의 효과를 재려면
// 먼저 "한 카드사에 부하가 온전히 걸렸을 때"의 기준선이 있어야 한다.
// (한쪽만 아프게 만드는 전파 실험은 k6/card_isolation.js 몫이다.)
//
// CARDS=CARD_CORP_A,CARD_CORP_B 로 주면 예전처럼 반반 분배한다.
const CARDS = (__ENV.CARDS || 'CARD_CORP_A').split(',').map(s => s.trim()).filter(Boolean);
const CARD_BASE_URL = { CARD_CORP_A: CARD_A, CARD_CORP_B: CARD_B };

// 한 카드사만 지속적으로 아프게 만든다 — 장애 전파 실험용.
//
// PROB 카오스가 양쪽에 균일하게 깔린 위에, 이 카드사만 추가로 계속 아프다.
// 균일 카오스를 그대로 두는 이유는 **건강한 쪽의 기준선이 이미 있기 때문**이다:
// prob 0.027이면 이론 완주율이 (1-0.1037)^3 = 72.0%이고, 건강한 카드사가 그 아래로
// 떨어진 만큼이 곧 아픈 카드사에서 넘어온 전파다.
//
// 매입에는 걸지 않는다(인증·승인만). 장애를 하나도 안 맞는 단계를 남겨두는
// CAPTURE_FAILURE 원칙과 같은 이유다.
const SICK_CARD    = __ENV.SICK_CARD || '';
const SICK_FAILURE = __ENV.SICK_FAILURE || 'SLOW_SUCCESS';
const SICK_PROB    = __ENV.SICK_PROB !== undefined ? parseFloat(__ENV.SICK_PROB) : 1.0;

// 카드사 이름을 메트릭 이름에 쓸 짧은 라벨로. CARD_CORP_A -> A
const label = company => company.replace('CARD_CORP_', '');

const TPS      = parseInt(__ENV.TPS)      || 150;
const DURATION = __ENV.DURATION           || '2m';

// 처리율의 분모는 **부하 구간**이어야 한다.
// k6가 카운터에 붙여주는 rate는 setup/teardown까지 포함한 전체 런 시간으로 나눈 값이라,
// 도착률을 150/s로 고정해 놓고도 145.9/s처럼 읽힌다. 그러면 "150 넣어서 몇 나왔나"를
// 비교하는 이 측정에서 분자와 분모의 기준이 어긋난다.
const LOAD_WINDOW_SEC = parseDuration(DURATION);

function parseDuration(text) {
  const matched = String(text).match(/(\d+(?:\.\d+)?)(ms|s|m|h)/g) || [];
  const unit = { ms: 0.001, s: 1, m: 60, h: 3600 };
  return matched.reduce((total, part) => {
    const [, value, suffix] = part.match(/(\d+(?:\.\d+)?)(ms|s|m|h)/);
    return total + parseFloat(value) * unit[suffix];
  }, 0) || 1;
}
// 3~5초짜리 응답이 몰리면 순간 동시성이 크게 튄다. 런 중에 VU를 증설하면
// 초기화가 늦어 iteration이 drop되므로, 처음부터 넉넉히 깔아둔다.
//
// 필요한 VU 수 = 도착률 × iteration 소요시간. 카드사 하나가 아프면 iteration이
// 몇 초로 늘어나 TPS×4로는 어림도 없다 — 250 TPS 실측에서 2,831개까지 증설되고도
// 1,864 iteration이 drop됐다. drop이 나면 실제 부하가 설정보다 낮아져서
// **두 설정을 같은 부하로 비교한다는 전제 자체가 깨진다.**
const PRE_VUS  = parseInt(__ENV.PRE_VUS)  || Math.max(600, TPS * 12);
const MAX_VUS  = parseInt(__ENV.MAX_VUS)  || Math.max(4000, TPS * 40);

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

// ── 집계 지표 ────────────────────────────────────────────────
// 설정 A/B를 하나의 숫자로 비교하려면 status로 쪼개지지 않은 p95가 필요하다.
// 성공이든 UNKNOWN이든 실패든, 응답이 온 건 전부 같은 통에 담는다 —
// 격리 장치의 값어치는 결국 이 분포의 꼬리를 얼마나 잘라내느냐이기 때문이다.
// (status별 내역은 T가 그대로 들고 있어서 원인 분해는 따로 할 수 있다.)
const AGG = {
  request: new Trend('sync_request_ms', true),
  approve: new Trend('sync_approve_ms', true),
  capture: new Trend('sync_capture_ms', true),
};
const syncAll = new Trend('sync_all_ms', true);

// ── 처리량 ───────────────────────────────────────────────────
// 개방 모델이라 도착률(TPS)은 설정값으로 고정된다. 따라서 k6의 iteration rate는
// 처리량이 아니라 "우리가 얼마나 밀어넣었나"일 뿐이다. 진짜 처리량은
// **끝까지 간 결제 수 / 초**다. 아래 4개로 유입분이 전부 어디로 갔는지 맞춰본다:
//   started = captured + (승인/매입 단계별 unknown) + rejected + httpError
const started   = new Counter('payments_started');
const approved  = new Counter('payments_approved');   // 승인까지 동기로 성공
const captured  = new Counter('payments_captured');   // 매입까지 동기로 성공 (완주)
const unknown   = new Counter('payments_unknown');    // 동기 경로 이탈 — 조회가 나중에 확정
const rejected  = new Counter('payments_rejected');   // 확정 실패 (4xx, Bulkhead/서킷 거절 포함)
const httpError = new Counter('payments_http_error'); // payment가 200을 못 준 경우

// ── 카드사별 분리 ────────────────────────────────────────────
// 전파 실험의 판정은 전부 여기서 난다. 합산 지표는 아픈 카드사가 끌어내린 평균이라
// "건강한 카드사가 무사했는가"라는 질문에 답하지 못한다.
// k6 메트릭은 init 단계에서만 만들 수 있는데, CARDS는 환경변수라 여기서 확정된다.
const PER_CARD = {};
CARDS.forEach(company => {
  const suffix = label(company);
  PER_CARD[company] = {
    latency:  new Trend(`card_${suffix}_sync_ms`, true),
    started:  new Counter(`card_${suffix}_started`),
    captured: new Counter(`card_${suffix}_captured`),
    unknown:  new Counter(`card_${suffix}_unknown`),
    rejected: new Counter(`card_${suffix}_rejected`),
    apiOk:    new Counter(`card_${suffix}_api_ok`),
  };
});

// 단계별 "의도한 성공" 응답. 카드사별 성공 RPS를 세는 데 쓴다.
const OK_OUTCOMES = new Set(['FDS_PASSED', 'APPROVED', 'SUCCEEDED']);

function record(phase, outcome, ms, company) {
  syncAll.add(ms);
  AGG[phase].add(ms);

  const perCard = PER_CARD[company];
  if (perCard) {
    perCard.latency.add(ms);
    if (OK_OUTCOMES.has(outcome)) perCard.apiOk.add(1);
  }

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

  // 부하가 가는 카드사에만 주입한다. 트래픽 없는 카드사에 룰을 걸어두면
  // 결과 파일만 보고 "양쪽 다 아팠나?" 하고 헷갈린다.
  CARDS.forEach(company => {
    const card = CARD_BASE_URL[company];
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
  console.log(`[setup] cards=${CARDS.join(',')} · capture failure=${CAPTURE_FAILURE}`);
  injectSickCard();
}

/**
 * 한 카드사만 지속 장애로 만든다. 균일 카오스 위에 덧씌우므로 setup 마지막에 건다.
 *
 * PROB=0으로 돌리면 이것만 남아 "정상 카드사 + 아픈 카드사" 순수 구도가 된다.
 */
function injectSickCard() {
  if (!SICK_CARD) return;

  const baseUrl = CARD_BASE_URL[SICK_CARD];
  if (!baseUrl) {
    // 오타난 카드사 이름은 조용히 무시되면 안 된다 — 장애 없는 런을 장애 런으로 착각한다.
    throw new Error(`[setup] unknown SICK_CARD=${SICK_CARD}`);
  }

  ['auth', 'approve'].forEach(endpoint => {
    http.post(`${baseUrl}/admin/failure`, JSON.stringify({
      endpoint, failure: SICK_FAILURE, remaining: REMAINING, triggerProbability: SICK_PROB,
    }), JSON_HDR);
  });
  console.log(`[setup] SICK ${SICK_CARD}: ${SICK_FAILURE} p=${SICK_PROB} on auth+approve`);
}

export function teardown() {
  [CARD_A, CARD_B, FDS, PAYMENT].forEach(url => http.del(`${url}/admin/failure`));
  console.log('[teardown] cleared all failures');
}

export default function () {
  const merchantId = `sync-${String(__VU).padStart(3, '0')}`;
  const orderId    = `sync-${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`;
  // 카드사가 하나면 그대로, 여럿이면 VU 번호로 균등 분배한다.
  const company    = CARDS[__VU % CARDS.length];

  started.add(1);
  PER_CARD[company].started.add(1);

  // ── 1) 인증 + FDS ─────────────────────────────────────────
  const res = http.post(
    `${PAYMENT}/v1/payment`,
    JSON.stringify({ orderId, merchantId, amount: 10000, cardCompany: company }),
    { ...JSON_HDR, timeout: '15s' }
  );

  if (res.status !== 200) {
    record('request', 'HTTP_ERROR', res.timings.duration, company);
    httpError.add(1);
    return;
  }

  const status     = res.json('status');
  const paymentKey = res.json('paymentKey');
  record('request', status, res.timings.duration, company);

  // UNKNOWN을 받으면 여기서 측정 완료 — 복구는 스케줄러가 알아서 한다.
  if (status !== 'FDS_PASSED' || !paymentKey) {
    countExit(status, company);
    return;
  }

  // ── 2) 승인 ───────────────────────────────────────────────
  const approveRes = http.post(
    `${PAYMENT}/v1/payment/${paymentKey}/approve`, null,
    { ...JSON_HDR, timeout: '15s' }
  );

  if (approveRes.status !== 200) {
    record('approve', 'HTTP_ERROR', approveRes.timings.duration, company);
    httpError.add(1);
    return;
  }

  const approveStatus = approveRes.json('status');
  record('approve', approveStatus, approveRes.timings.duration, company);

  if (approveStatus !== 'APPROVED') {
    countExit(approveStatus, company);
    return;
  }
  approved.add(1);

  // ── 3) 매입 ───────────────────────────────────────────────
  // 매입 상태는 PaymentIntent가 아니라 CAPTURE 트랜잭션에 있다.
  const captureRes = http.post(
    `${PAYMENT}/v1/payment/${paymentKey}/capture`, null,
    { ...JSON_HDR, timeout: '15s' }
  );

  if (captureRes.status !== 200) {
    record('capture', 'HTTP_ERROR', captureRes.timings.duration, company);
    httpError.add(1);
    return;
  }

  const captureStatus = captureRes.json('captureStatus');
  record('capture', captureStatus, captureRes.timings.duration, company);

  if (captureStatus === 'SUCCEEDED') {
    captured.add(1);
    PER_CARD[company].captured.add(1);
  } else {
    countExit(captureStatus, company);
  }
}

/**
 * 동기 경로를 벗어난 사유를 센다.
 *
 * UNKNOWN과 확정 실패를 반드시 갈라야 한다 — UNKNOWN은 조회가 나중에 성공으로 확정할 수도
 * 있는 "미정"이고, 확정 실패는 그 자리에서 죽은 결제다. Bulkhead·서킷 거절은
 * ExternalCallExecutor가 확정 실패로 분류하므로 rejected로 잡힌다. 격리 장치를 켜면
 * unknown이 rejected로 옮겨가는 게 정상이고, 그 교환이 이득인지가 이 측정의 쟁점이다.
 */
function countExit(status, company) {
  const perCard = PER_CARD[company];
  if (String(status).startsWith('UNKNOWN')) {
    unknown.add(1);
    if (perCard) perCard.unknown.add(1);
  } else {
    rejected.add(1);
    if (perCard) perCard.rejected.add(1);
  }
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

  const iter  = m.iterations ? m.iterations.values : {};
  const count = name => (m[name] ? m[name].values.count : 0);
  const perSec = n => Math.round(10 * n / LOAD_WINDOW_SEC) / 10;

  const startedCount = count('payments_started');
  const pct = n => (startedCount ? Math.round(1000 * n / startedCount) / 10 : null);

  // ── 처리량: 두 층위를 따로 센다 ──────────────────────────────
  // API 층  payment가 응답한 요청 수 / 초. 부하 테스트에서 말하는 보통의 RPS다.
  //         결제 하나가 인증·승인·매입 3번을 부르므로 결제 수보다 크다.
  // 결제 층  끝까지 통과한 결제 수 / 초. 도메인 관점의 처리량.
  //
  // 둘 다 필요하다 — 격리 장치는 API 요청을 빨리 거절해서 RPS를 오히려 **올릴** 수 있고
  // (거절도 응답이다), 그 대가로 완주하는 결제는 줄어든다. 한 층만 보면 그 교환이 안 보인다.
  const apiResponses = m.sync_all_ms ? m.sync_all_ms.values.count : 0;
  const apiOk = ['request_FDS_PASSED_ms', 'approve_APPROVED_ms', 'capture_SUCCEEDED_ms']
      .reduce((sum, name) => sum + (m[name] ? m[name].values.count : 0), 0);

  // 카드사별 같은 지표. 전파 실험의 판정이 전부 여기서 난다.
  const perCardSummary = () => {
    const out = {};
    CARDS.forEach(company => {
      const s = label(company);
      const cardStarted = count(`card_${s}_started`);
      const cardCaptured = count(`card_${s}_captured`);
      const latency = stats(`card_${s}_sync_ms`);
      out[company] = {
        sick: company === SICK_CARD,
        // 처리한 요청 수. 완주율은 "들어온 것 중 몇 %"라 유입이 흔들리면 비교가 안 되는데,
        // 이 개수는 카드사가 실제로 받아낸 일의 양을 그대로 센다.
        apiResponses: latency ? latency.count : 0,
        apiOk: count(`card_${s}_api_ok`),
        rps: perSec(latency ? latency.count : 0),
        okRps: perSec(count(`card_${s}_api_ok`)),
        startedRps: perSec(cardStarted),
        completedRps: perSec(cardCaptured),
        completedPct: cardStarted ? Math.round(1000 * cardCaptured / cardStarted) / 10 : null,
        started: cardStarted,
        captured: cardCaptured,
        unknown: count(`card_${s}_unknown`),
        rejected: count(`card_${s}_rejected`),
        latency: latency,
      };
    });
    return out;
  };

  const summary = {
    params: {
      prob: PROB, tps: TPS, duration: DURATION,
      cards: CARDS, captureFailure: CAPTURE_FAILURE,
      sickCard: SICK_CARD || null,
      sickFailure: SICK_CARD ? SICK_FAILURE : null,
      maxConnPerRoute: __ENV.CARD_MAX_CONN_PER_ROUTE || '128 (default)',
      sharedPool: (__ENV.CARD_SHARED_POOL || 'false') === 'true',
      // 어떤 격리 설정으로 돌았는지 결과에 박아둔다. A/B 파일을 나중에 헷갈리지 않으려고.
      bulkheadMax:   __ENV.CARD_BULKHEAD_MAX    || '48 (default)',
      cbFailureRate: __ENV.CARD_CB_FAILURE_RATE || '50 (default)',
      cbSlowRate:    __ENV.CARD_CB_SLOW_RATE    || '50 (default)',
    },
    // ── 비교 기준 ──
    // rps / okRps      처리량 (API 층). 부하 테스트에서 말하는 보통의 RPS.
    // completedRps     처리량 (결제 층). 끝까지 통과한 결제/초.
    // p95              성공·UNKNOWN·실패를 한 통에 담은 동기 응답 지연.
    headline: {
      rps:          perSec(apiResponses),
      okRps:        perSec(apiOk),
      okRate:       apiResponses ? Math.round(1000 * apiOk / apiResponses) / 10 : null,
      offeredRps:   TPS,
      completedRps: perSec(count('payments_captured')),
      completedPct: pct(count('payments_captured')),
      syncP95Ms:    stats('sync_all_ms') ? stats('sync_all_ms').p95 : null,
      syncP99Ms:    stats('sync_all_ms') ? stats('sync_all_ms').p99 : null,
    },
    throughput: {
      loadWindowSec:     LOAD_WINDOW_SEC,
      apiResponses:      apiResponses,
      apiOk:             apiOk,
      apiRps:            perSec(apiResponses),
      apiOkRps:          perSec(apiOk),
      started:           startedCount,
      approved:          count('payments_approved'),
      captured:          count('payments_captured'),
      unknown:           count('payments_unknown'),
      rejected:          count('payments_rejected'),
      httpError:         count('payments_http_error'),
      startedRps:        perSec(startedCount),
      capturedRps:       perSec(count('payments_captured')),
      // k6가 자체 계산한 값. 분모가 setup/teardown 포함 전체 런 시간이라 위 값보다 낮게 나온다.
      k6IterationRps:    iter.rate ? Math.round(iter.rate * 10) / 10 : null,
      droppedIterations: count('dropped_iterations'),
      preAllocatedVUs:   PRE_VUS,
      maxVusUsed:        m.vus_max ? m.vus_max.values.max : null,
    },
    // ── 측정 유효성 ──
    // 부하 생성기가 먼저 무너지면 그 런의 지연 수치는 시스템이 아니라 k6를 재게 된다.
    // 판정 근거를 결과 파일 안에 박아둬야 나중에 "그때 그 38초"를 다시 안 믿는다.
    //   droppedIterations > 0  VU가 모자라 실제 부하가 설정보다 낮았다
    //   httpError > 0          k6가 15s 요청 타임아웃으로 먼저 끊었다
    // httpError는 0/1 판정이 아니라 비율로 본다 — 포화 직전에는 몇 건씩 섞이는 게
    // 정상이고, 그걸 전부 무효로 처리하면 쓸 수 있는 런이 남지 않는다. 0.5%가 넘어가면
    // 그때부터는 k6가 먼저 끊은 요청이 분포를 지배한다.
    // 호스트가 멈추면(VU를 과하게 깔아 스왑이 나는 경우 등) k6 프로세스가 통째로 정지했다가
    // 풀리면서, 진행 중이던 모든 요청의 duration에 그 정지 시간이 얹힌다. 그러면 인증·승인·매입
    // 처럼 무관한 버킷의 max가 같은 값으로 나란히 찍힌다.
    //
    // 판정은 단순하다: **요청 타임아웃이 15초라 어떤 응답도 15초를 넘을 수 없다.**
    // 그보다 큰 값이 있으면 그 런의 지연은 물론 부하 프로파일 자체를 믿으면 안 된다.
    validity: {
      hostStalled: stats('sync_all_ms') ? stats('sync_all_ms').max > 20000 : false,
      maxLatencyMs: stats('sync_all_ms') ? stats('sync_all_ms').max : null,
      testRunSec: data.state && data.state.testRunDurationMs
          ? Math.round(data.state.testRunDurationMs / 1000) : null,
      loadGeneratorSaturated:
          count('dropped_iterations') > 0
          || (startedCount ? count('payments_http_error') / startedCount > 0.005 : false),
      droppedIterations: count('dropped_iterations'),
      httpError: count('payments_http_error'),
      httpErrorPct: startedCount
          ? Math.round(1000 * count('payments_http_error') / startedCount) / 10 : null,
      vuHeadroom: m.vus_max ? PRE_VUS - m.vus_max.values.max : null,
    },
    // ── 전파 판정 ──
    // 아픈 카드사가 아니라 **건강한 카드사**의 줄을 봐야 한다. 균일 카오스만 맞은
    // 카드사의 이론 완주율은 (1-실효장애율)^3이고, 거기서 내려간 만큼이 전파다.
    //
    // 지수가 3인 이유: 장애가 걸린 단계가 인증·FDS·승인 셋이고 매입은 대조군이다.
    // 실측이 이 값과 붙으면 자기 손실이 0이라는 뜻이다 (단일 카드사 150 TPS에서 확인).
    theoreticalCompletedPct:
        Math.round(1000 * Math.pow(1 - (1 - Math.pow(1 - PROB, 4)), 3)) / 10,
    byCard: perCardSummary(),
    // 소켓 타임아웃(3s)이 동기 응답의 이론적 상한이다. UNKNOWN_* 의 p95가 이 값을
    // 크게 넘으면 초과분은 카드사가 아니라 우리 쪽 대기다.
    socketTimeoutMs: 3000,
    syncLatencyOverall: {
      all:     stats('sync_all_ms'),
      request: stats('sync_request_ms'),
      approve: stats('sync_approve_ms'),
      capture: stats('sync_capture_ms'),
    },
    syncLatency: out,
  };

  const path = __ENV.SUMMARY_OUT || 'payment-sync-latency-summary.json';
  return {
    [path]: JSON.stringify(summary, null, 2),
    stdout: JSON.stringify(summary, null, 2) + '\n',
  };
}
