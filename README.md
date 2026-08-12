# Payment System

카드 결제의 승인 → 매입 → 환불 → 정산 흐름을 다루는 멀티서비스 결제 시스템입니다.
분산 환경에서 외부 호출 실패가 발생해도 데이터 정합성이 깨지지 않도록 설계하는 데
초점을 맞췄습니다.

## 구성

| 서비스 | 포트 | 역할 |
|---|---|---|
| `merchant` | 8081 | 가맹점 (웹훅 수신) |
| `payment` | 8082 | 결제 오케스트레이션 (auth → FDS → capture, 환불, 정산, 복구) |
| `fds` | 8083 | 이상거래 탐지 |
| `card` | 8084 | 카드 승인/매입/환불 (CARD_CORP_A) |
| `card-b` | 8085 | 카드 승인/매입/환불 (CARD_CORP_B) |

각 서비스는 독립 DB(MySQL, Flyway 관리)를 가지며 Docker Compose로 함께 실행됩니다.

## 주요 구현

- **결제 플로우** — `POST /v1/payment`(인증+FDS) → `POST /v1/payment/{key}/approve`(승인) →
  `POST /admin/captures/run`(매입, 배치).
  환불은 `POST /v1/payment/refund`, 승인/매입 취소는 `POST /v1/payment/{key}/cancel`.

- **UNKNOWN 상태 + 자동 복구** — 외부 호출 응답이 유실되면 성공/실패로 단정하지 않고
  UNKNOWN으로 둔다. 스케줄러가 결제/환불 요청 시 발급한 매칭 키(`cardRequestRef`)로
  카드사/FDS에 재조회(inquiry)해서 SUCCEEDED/FAIL로 확정한다. 모든 상태 전이는 멱등하게
  처리되어 중복 실행에 안전하다.

- **Append-only 원장** — 매입/환불 시 계정 잔액을 직접 UPDATE하지 않고 원장 항목만
  INSERT한다. 배경 스케줄러가 주기적으로 잔액 스냅샷을 갱신해, 핫 계정 row의 락 경합을
  제거했다.

- **카드사 장애 격리** — 카드사별 적응형 동시성 제한(Gradient2Limit)을 적용해, 한 카드사가
  느려지면 해당 카드사 요청을 빠르게 실패시켜 공유 스레드풀 고갈을 막는다. 정상 카드사는
  영향받지 않는다.

- **복식부기 원장 + 정산** — 매입/환불/청산/정산/지급을 차변·대변 균형이 맞는 원장으로
  기록하고, 카드사 정산 파일과 대사(reconciliation)한다.

- **수수료 비례 환급 / 환불 리스크 플래그** — 부분환불 시 수수료를 비례 환급하고,
  환불로 가맹점 잔액이 음수가 되면 별도 테이블에 기록한다(환불은 차단하지 않음).

## 실행

```bash
docker compose up --build
```

서비스가 모두 뜨면 정합성 검증 엔드포인트로 상태를 확인할 수 있다.

```bash
curl http://localhost:8082/admin/verify/pg-internal   # 상태/UNKNOWN/멱등키 정합성
curl http://localhost:8082/admin/verify/ledger        # 복식부기 균형 + 미수금 잔액
```

## 테스트 — 결제+환불 카오스 (장애 환경 정합성 검증)

결제와 환불을 10:3 비율로 발생시키면서, auth/fds/capture 세 호출 지점에 4종 장애(응답 지연
전/후 유실, 500 에러, connect 실패)를 요청마다 독립적으로 균일 확률로 랜덤 주입한다. 장애가
섞여도 모든 UNKNOWN이 복구되어 다음 4계층 정합성이 유지되는지 검증한다.

1. **PG 내부 불변식** — UNKNOWN 잔존/원장 소스 누락/멱등키 고착 등
2. **PG↔카드사 exactly-once** — `cardRequestRef` 집합 대사(이중/고아 청구가 없는지)
3. **원장 복식부기 균형**
4. **정산파일 재조정** — 카드사가 독립 생성한 정산 CSV와의 5-case 대사

[k6](https://k6.io)가 필요하다.

### 한 번에 실행 (권장)

DB 초기화 → 부하+장애 주입 → 수렴 대기 → 정합성 4계층 검증까지 자동 수행한다.

```bash
./scripts/run-payment-refund-chaos.sh              # 기본값: 확률 0.027, 50 VU, 2분
./scripts/run-payment-refund-chaos.sh 0.05 50 3m   # [확률] [VU] [기간]
```

결과는 `results/*_payment_refund_chaos_consistency_*.json`에 저장되며, 4계층이 전부
통과해야 `passed: true`. `reference_post_convergence_inquiry` 필드는 수렴 완료 후
inquiry 누적 통계(참고용, 판정에는 사용하지 않음)를 담는다.

### 수동 단계별 실행

```bash
# 1. 서비스 기동
docker compose up -d

# 2. 카오스 테스트 실행 (장애 주입 → 부하 → 자동 해제)
k6 run k6/payment_refund_chaos.js

# 3. 복구 스케줄러를 수동으로 빠르게 돌려 UNKNOWN/웹훅 잔량 배수
curl -X POST http://localhost:8082/admin/scheduler/run-now   # 잔량이 0이 될 때까지 반복

# 4. 정합성 확인 — passed: true, unbalancedPostings: 0, unknownRemaining: 0
curl http://localhost:8082/admin/verify/pg-internal
curl http://localhost:8082/admin/verify/ledger
```

## 상세 기술
- https://silent-hail-00b.notion.site/PG-39d85313ee8b808b9f30c34da11946c9
