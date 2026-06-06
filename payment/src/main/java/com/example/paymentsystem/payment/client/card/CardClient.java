package com.example.paymentsystem.payment.client.card;

import com.example.paymentsystem.payment.dto.RefundInquiryResponse;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CardClient {

    @Qualifier("cardRestClient")
    private final RestClient cardRestClient;

    private final Retry cardAuthRetry;
    private final Retry cardCaptureRetry;
    private final Retry cardRefundRetry;

    public CardAuthResponse authorize(CardAuthRequest request) {
        Supplier<CardAuthResponse> supplier = () ->
                cardRestClient.post()
                        .uri("/v1/authorizations")
                        .body(request)
                        .retrieve()
                        .body(CardAuthResponse.class);

        return Retry.decorateSupplier(cardAuthRetry, supplier).get();
    }

    public CardCaptureResponse capture(String authorizationId, CardCaptureRequest request) {
        Supplier<CardCaptureResponse> supplier = () ->
                cardRestClient.post()
                        .uri("/v1/authorizations/{authorizationId}/capture", authorizationId)
                        .body(request)
                        .retrieve()
                        .body(CardCaptureResponse.class);

        return Retry.decorateSupplier(cardCaptureRetry, supplier).get();
    }

    public CardRefundResponse refund(String captureId, CardRefundRequest request) {
        Supplier<CardRefundResponse> supplier = () ->
                cardRestClient.post()
                        .uri("/v1/authorizations/{captureId}/refund", captureId)
                        .body(request)
                        .retrieve()
                        .body(CardRefundResponse.class);

        return Retry.decorateSupplier(cardRefundRetry, supplier).get();
    }

    public AuthInquiryResponse inquiryAuth(String authIdempotentKey) {
        return cardRestClient.get()
                .uri("/v1/authorizations/inquiries/{authIdempotentKey}", authIdempotentKey)
                .retrieve()
                .body(AuthInquiryResponse.class);
    }

    public CaptureInquiryResponse inquiryCapture(String captureIdempotentKey) {
        return cardRestClient.get()
                .uri("/v1/authorizations/captures/inquiries/{captureIdempotentKey}", captureIdempotentKey)
                .retrieve()
                .body(CaptureInquiryResponse.class);
    }

    public RefundInquiryResponse inquiryRefund(String refundIdempotentKey) {
        return cardRestClient.get()
                .uri("/v1/authorizations/refund/inquiries/{refundIdempotentKey}", refundIdempotentKey)
                .retrieve()
                .body(RefundInquiryResponse.class);
    }
}
