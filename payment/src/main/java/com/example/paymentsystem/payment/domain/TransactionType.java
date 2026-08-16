package com.example.paymentsystem.payment.domain;

public enum TransactionType {
    /** 인증 — 카드사 본인확인. 승인이 아니다(카드 한도를 잡지 않는다). */
    AUTH,
    FDS,
    /** 승인 — 카드사 승인요청. 성공하면 사용자에게 결제완료(APPROVED). */
    APPROVE,
    /** 매입 — 카드사 대금 청구. 동기 경로 밖 배치이며, 원장 기표는 이 시점에 일어난다. */
    CAPTURE,
    /**
     * 승인취소 — 매입 전 승인을 되돌린다.
     *
     * <p>다른 타입과 달리 <b>UNKNOWN이 될 수 없다.</b> 취소는 확정 응답만 결과로 인정하고,
     * 응답을 못 받으면 트랜잭션째 롤백해 이 행 자체가 남지 않는다. 그래서 REQUESTED로
     * 방치되는 일도, 조회(inquiry) 대상이 되는 일도 없다.
     */
    CANCEL
}
