package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.domain.CardCompany;
import com.netflix.concurrency.limits.Limiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CardClient {

    private final Map<CardCompany, RestClient> cardRestClients;
    private final CardConcurrencyLimiterRegistry limiterRegistry;

    public CardAuthResponse authenticate(CardCompany company, CardAuthRequest request) {
        Limiter.Listener token = acquireOrThrow(company);
        try {
            CardAuthResponse response = restClient(company).post()
                    .uri("/v1/authentications")
                    .body(request)
                    .retrieve()
                    .body(CardAuthResponse.class);
            token.onSuccess();
            return response;
        } catch (Exception e) {
            token.onDropped();
            throw e;
        }
    }

    public CardApproveResponse approve(CardCompany company, String authenticationId, CardApproveRequest request) {
        Limiter.Listener token = acquireOrThrow(company);
        try {
            CardApproveResponse response = restClient(company).post()
                    .uri("/v1/authentications/{authenticationId}/approve", authenticationId)
                    .body(request)
                    .retrieve()
                    .body(CardApproveResponse.class);
            token.onSuccess();
            return response;
        } catch (Exception e) {
            token.onDropped();
            throw e;
        }
    }

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

    private Limiter.Listener acquireOrThrow(CardCompany company) {
        Optional<Limiter.Listener> token = limiterRegistry.acquire(company);
        if (token.isEmpty()) {
            throw new ConcurrencyLimitExceededException(company);
        }
        return token.get();
    }

    private RestClient restClient(CardCompany company) {
        return cardRestClients.get(company);
    }
}
