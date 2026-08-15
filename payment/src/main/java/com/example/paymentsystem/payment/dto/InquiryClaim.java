package com.example.paymentsystem.payment.dto;

import com.example.paymentsystem.payment.domain.PaymentTransaction;
import java.time.Duration;

/**
 * 조회 한 번을 선점한 결과.
 *
 * @param transaction intent까지 붙여 읽은 대상 (트랜잭션 밖에서도 카드사를 읽을 수 있다)
 * @param nextDelay   이번 시도가 실패했을 때 다음 조회까지의 간격. DB에 적힌 값과 같으므로
 *                    큐와 DB가 같은 시각을 보게 된다.
 */
public record InquiryClaim(PaymentTransaction transaction, Duration nextDelay) {
}
