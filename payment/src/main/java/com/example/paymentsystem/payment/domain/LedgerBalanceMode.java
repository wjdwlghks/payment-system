package com.example.paymentsystem.payment.domain;

/**
 * 매입 기표가 계정 잔액을 언제 반영하는가. <b>측정용 스위치다</b> — 운영 기본값은 {@link #SNAPSHOT}이고,
 * 나머지 둘은 지금 방식이 무엇을 고쳤는지 같은 코드베이스 위에서 재보기 위해 남겨둔 과거 구현이다.
 *
 * <p>과거 커밋을 체크아웃해서 재지 않는 이유는 그 시절 매입이 PG 배치였고 환불이 있었고 API 모양이
 * 달라서, 그대로 재면 원장 전략이 아니라 그 뒤로 바뀐 전부(Resilience4j, inquiry 큐, 가맹점별 매입)를
 * 같이 재게 되기 때문이다.
 *
 * <p>분기는 <b>매입 기표에만</b> 건다. 청산·정산·지급도 원래는 인라인이었지만 저빈도 배치라
 * 경합의 주체가 아니다. 모든 결제가 예외 없이 지나가는 지점은 매입 하나다.
 */
public enum LedgerBalanceMode {

    /**
     * 기표와 같은 트랜잭션에서 {@code account.balance}를 UPDATE한다. 원래 방식.
     *
     * <p>매입 한 건이 건드리는 계정은 CARD_NETWORK_RECEIVABLE · FEE_REVENUE · MERCHANT_PENDING인데,
     * 앞의 둘은 <b>전 결제가 공유하는 단 한 행</b>이다. 그 행의 X-lock을 커밋까지 쥐고 있으므로
     * 매입은 그 지점에서 직렬화된다.
     */
    INLINE,

    /**
     * INLINE과 같되 글로벌 계정을 N개 버킷으로 쪼개 경합을 나눈다. paymentKey 해시로 버킷을 고른다.
     *
     * <p>잔액은 버킷 합계로만 읽을 수 있게 되고, 청산·정산처럼 전체를 봐야 하는 경로는 전부
     * 합산을 거쳐야 한다 — 경합을 줄인 대가로 읽는 쪽이 복잡해진다.
     */
    SHARDED,

    /**
     * 원장 entry만 INSERT하고 잔액은 {@code AccountBalanceFlusher}가 주기적으로 스냅샷 갱신한다.
     * 핫패스에서 계정 행을 아예 건드리지 않으므로 잠글 것이 없다. 현재 운영 방식.
     */
    SNAPSHOT
}
