package com.example.paymentsystem.payment.service;

import java.time.Duration;

/**
 * UNKNOWN 재조회 백오프 사다리.
 *
 * <p>웹훅 백오프({@code WebhookService.backoff})와 형태가 다르다. 웹훅은 7회 넘으면 DEAD로
 * <b>포기</b>하므로 간격을 무한정 키워도 되지만, UNKNOWN은 포기할 수가 없다 — 카드사 쪽에는
 * 승인이 나 있을 수도 있는 돈이다. 포기할 수 없으면 간격에 <b>천장</b>을 씌우는 수밖에 없고,
 * 그래서 여기는 "증가 + 캡 + 무한 재시도"다.
 *
 * <p>캡이 정하는 것은 두 가지다. 하나는 <b>카드사가 복구된 뒤 그 사실을 알아채기까지의 최악
 * 지연</b>이고, 다른 하나는 <b>적체된 건들이 만드는 상시 조회 부하</b>(= 적체 건수 / 캡)다.
 * 캡을 늘리면 죽은 카드사를 덜 때리는 대신 복구 감지가 느려진다.
 *
 * <p>지금은 단일 사다리를 쓴다. 사용자가 기다리는 AUTH/APPROVE는 짧게, 대사가 최종 백스톱인
 * CAPTURE는 길게 가져가는 단계별 차등도 가능하지만, 먼저 이 값으로 실측한 뒤에 나눈다.
 */
final class InquiryBackoff {

    private static final Duration CAP = Duration.ofMinutes(10);

    private InquiryBackoff() {
    }

    static Duration of(int attempts) {
        return switch (attempts) {
            case 0, 1 -> Duration.ofSeconds(3);
            case 2 -> Duration.ofSeconds(10);
            case 3 -> Duration.ofSeconds(30);
            case 4 -> Duration.ofMinutes(2);
            default -> CAP;
        };
    }
}
