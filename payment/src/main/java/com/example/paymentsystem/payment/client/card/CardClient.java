package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.domain.CardCompany;
import com.example.paymentsystem.payment.dto.RefundInquiryResponse;
import com.netflix.concurrency.limits.Limiter;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CardClient {

    private final Map<CardCompany, RestClient> cardRestClients;
    private final CardConcurrencyLimiterRegistry limiterRegistry;

    private final Retry cardAuthRetry;
    private final Retry cardCaptureRetry;
    private final Retry cardRefundRetry;

    public CardAuthResponse authorize(CardCompany company, CardAuthRequest request) {
        Limiter.Listener token = acquireOrThrow(company);
        try {
            Supplier<CardAuthResponse> supplier = () ->
                    restClient(company).post()
                            .uri("/v1/authorizations")
                            .body(request)
                            .retrieve()
                            .body(CardAuthResponse.class);
            CardAuthResponse response = Retry.decorateSupplier(cardAuthRetry, supplier).get();
            token.onSuccess();
            return response;
        } catch (Exception e) {
            token.onDropped();
            throw e;
        }
    }

    public CardCaptureResponse capture(CardCompany company, String authorizationId, CardCaptureRequest request) {
        Limiter.Listener token = acquireOrThrow(company);
        try {
            Supplier<CardCaptureResponse> supplier = () ->
                    restClient(company).post()
                            .uri("/v1/authorizations/{authorizationId}/capture", authorizationId)
                            .body(request)
                            .retrieve()
                            .body(CardCaptureResponse.class);
            CardCaptureResponse response = Retry.decorateSupplier(cardCaptureRetry, supplier).get();
            token.onSuccess();
            return response;
        } catch (Exception e) {
            token.onDropped();
            throw e;
        }
    }

    public CardRefundResponse refund(CardCompany company, String captureId, CardRefundRequest request) {
        Limiter.Listener token = acquireOrThrow(company);
        try {
            Supplier<CardRefundResponse> supplier = () ->
                    restClient(company).post()
                            .uri("/v1/authorizations/{captureId}/refund", captureId)
                            .body(request)
                            .retrieve()
                            .body(CardRefundResponse.class);
            CardRefundResponse response = Retry.decorateSupplier(cardRefundRetry, supplier).get();
            token.onSuccess();
            return response;
        } catch (Exception e) {
            token.onDropped();
            throw e;
        }
    }

    public CardVoidResponse voidAuth(CardCompany company, String authId) {
        Limiter.Listener token = acquireOrThrow(company);
        try {
            CardVoidResponse response = restClient(company).post()
                    .uri("/v1/authorizations/{authId}/void", authId)
                    .retrieve()
                    .body(CardVoidResponse.class);
            token.onSuccess();
            return response;
        } catch (Exception e) {
            token.onDropped();
            throw e;
        }
    }

    public AuthInquiryResponse inquiryAuth(CardCompany company, String authIdempotentKey) {
        return restClient(company).get()
                .uri("/v1/authorizations/inquiries/{authIdempotentKey}", authIdempotentKey)
                .retrieve()
                .body(AuthInquiryResponse.class);
    }

    public CaptureInquiryResponse inquiryCapture(CardCompany company, String captureIdempotentKey) {
        return restClient(company).get()
                .uri("/v1/authorizations/captures/inquiries/{captureIdempotentKey}", captureIdempotentKey)
                .retrieve()
                .body(CaptureInquiryResponse.class);
    }

    public RefundInquiryResponse inquiryRefund(CardCompany company, String refundIdempotentKey) {
        return restClient(company).get()
                .uri("/v1/authorizations/refund/inquiries/{refundIdempotentKey}", refundIdempotentKey)
                .retrieve()
                .body(RefundInquiryResponse.class);
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
