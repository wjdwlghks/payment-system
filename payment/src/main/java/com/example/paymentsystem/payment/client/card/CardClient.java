package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.domain.CardCompany;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CardClient {

    private final Map<CardCompany, RestClient> cardRestClients;
    private final BulkheadRegistry bulkheadRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CardAuthResponse authenticate(CardCompany company, CardAuthRequest request) {
        return guarded(company, () -> restClient(company).post()
                .uri("/v1/authentications")
                .body(request)
                .retrieve()
                .body(CardAuthResponse.class));
    }

    public CardApproveResponse approve(CardCompany company, String authenticationId, CardApproveRequest request) {
        return guarded(company, () -> restClient(company).post()
                .uri("/v1/authentications/{authenticationId}/approve", authenticationId)
                .body(request)
                .retrieve()
                .body(CardApproveResponse.class));
    }

    public CardCaptureResponse capture(CardCompany company, String approvalId, CardCaptureRequest request) {
        return guarded(company, () -> restClient(company).post()
                .uri("/v1/approvals/{approvalId}/capture", approvalId)
                .body(request)
                .retrieve()
                .body(CardCaptureResponse.class));
    }

    /**
     * 승인취소. <b>조회와 마찬가지로 Bulkhead/서킷으로 감싸지 않는다.</b>
     *
     * <p>로컬 거절은 {@code ExternalCallExecutor}에서 확정 실패로 분류되는데, 취소에서 그건
     * 치명적이다. 취소는 "다시 시작하면 되는" 요청이 아니라 이미 승인된 결제를 되돌리는
     * <b>유일한 출구</b>라, 여기서 막히면 카드사에 승인이 살아 있는 채로 갈 곳이 없어진다.
     * 결제 실패는 사용자가 다시 결제하면 되지만 취소 실패는 그런 우회로가 없다.
     *
     * <p>보호 장치를 떼도 안전한 이유는 조회와 같다 — 취소는 저빈도이고, 커넥션 풀의
     * per-route 상한이 물리적 방어선으로 남는다.
     */
    public CardCancelResponse cancel(CardCompany company, String approvalId, CardCancelRequest request) {
        return restClient(company).post()
                .uri("/v1/approvals/{approvalId}/cancel", approvalId)
                .body(request)
                .retrieve()
                .body(CardCancelResponse.class);
    }

    // ── 조회(inquiry)는 의도적으로 감싸지 않는다 ─────────────────────────────
    //
    // 조회를 Bulkhead/서킷으로 막으면 그 거절이 ExternalCallExecutor에서 확정 실패로
    // 분류되고, 그러면 카드사에 물어보지도 못한 건을 failAuth로 영구 확정해버린다.
    // 카드사가 이미 처리했을 수도 있는 건을 말이다.
    //
    // 게다가 조회는 복구 경로다. 카드사가 아플 때가 UNKNOWN이 가장 많이 쌓여
    // 복구가 가장 필요한 때인데, 바로 그때 조회를 막으면 상황을 악화시킨다.
    // 조회는 저빈도이고 InquiryQueue가 자체 세마포어로 이미 동시성을 제한한다.

    public AuthInquiryResponse inquiryAuth(CardCompany company, String cardRequestRef) {
        return restClient(company).get()
                .uri("/v1/authentications/inquiries/{cardRequestRef}", cardRequestRef)
                .retrieve()
                .body(AuthInquiryResponse.class);
    }

    public ApproveInquiryResponse inquiryApprove(CardCompany company, String cardRequestRef) {
        return restClient(company).get()
                .uri("/v1/authentications/approvals/inquiries/{cardRequestRef}", cardRequestRef)
                .retrieve()
                .body(ApproveInquiryResponse.class);
    }

    public CaptureInquiryResponse inquiryCapture(CardCompany company, String cardRequestRef) {
        return restClient(company).get()
                .uri("/v1/captures/inquiries/{cardRequestRef}", cardRequestRef)
                .retrieve()
                .body(CaptureInquiryResponse.class);
    }

    /**
     * 카드사별 Bulkhead + CircuitBreaker로 감싼다.
     *
     * <p>인스턴스는 카드사 이름으로 레지스트리에서 꺼낸다 — 어노테이션은 이름이 정적이라
     * enum 값으로 갈라지는 이 구조에 맞지 않는다. 없으면 {@code configs.default}를 물려받아
     * 즉석에서 만들어지므로 카드사를 추가해도 설정을 손댈 필요가 없다.
     *
     * <p>서킷이 바깥이다. 열려 있으면 Bulkhead 퍼밋조차 쓰지 않고 즉시 거절된다.
     */
    private <T> T guarded(CardCompany company, Supplier<T> call) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(company.name());
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(company.name());
        return circuitBreaker.executeSupplier(Bulkhead.decorateSupplier(bulkhead, call));
    }

    private RestClient restClient(CardCompany company) {
        return cardRestClients.get(company);
    }
}
