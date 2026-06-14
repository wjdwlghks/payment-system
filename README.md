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

- **결제 플로우** — `POST /v1/payment`(승인+FDS) → `POST /v1/payment/{key}/confirm`(매입).
  환불은 `POST /v1/payment/refund`, 승인/매입 취소는 `POST /v1/payment/{key}/cancel`.

- **UNKNOWN 상태 + 자동 복구** — 외부 호출 응답이 유실되면 성공/실패로 단정하지 않고
  UNKNOWN으로 둔다. 스케줄러가 카드사에 재조회(inquiry)하거나 재시도(retry)해서
  SUCCEEDED/FAIL로 확정한다. 모든 상태 전이는 멱등하게 처리되어 중복 실행에 안전하다.

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

결제와 환불을 10:3 비율로 발생시키면서, 모든 외부 호출 단계(auth/fds/capture/refund)에
4종 장애(응답 지연, 응답 유실, 500 에러, 연결 실패)를 확률적으로 주입한다. 장애가 섞여도
모든 UNKNOWN이 복구되어 최종 원장이 일치하는지 검증한다.

[k6](https://k6.io)가 필요하다.

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
- https://silent-hail-00b.notion.site/Payment-system-37d85313ee8b8064a960d0337b00dcfe?pvs=74
