package com.example.paymentsystem.payment.domain;

public enum TransactionType {
    /** 인증 — 카드사 본인확인. 승인이 아니다(카드 한도를 잡지 않는다). */
    AUTH,
    FDS,
    /** 승인 — 카드사 승인요청. 성공하면 사용자에게 결제완료(APPROVED). */
    APPROVE,
    /** 매입 — 카드사 대금 청구. 동기 경로 밖 배치이며, 원장 기표는 이 시점에 일어난다. */
    CAPTURE
}
