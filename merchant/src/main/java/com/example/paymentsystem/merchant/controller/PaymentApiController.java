package com.example.paymentsystem.merchant.controller;

import com.example.paymentsystem.merchant.client.PaymentClient;
import com.example.paymentsystem.merchant.component.PaymentFlow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    private final PaymentClient paymentClient;
    private final PaymentFlow paymentFlow;

    public PaymentApiController(PaymentClient paymentClient, PaymentFlow paymentFlow) {
        this.paymentClient = paymentClient;
        this.paymentFlow = paymentFlow;
    }

    /**
     * 부하 생성기가 호출하는 유일한 진입점. 이 요청 이후의 승인·매입은 가맹점이 스스로 이어간다
     * (동기 응답이 FDS_PASSED면 곧바로, UNKNOWN이면 웹훅을 받고).
     *
     * <p>{@code begin}이 호출 <b>직전에</b> 시계를 켠다. 응답을 받은 뒤에 켜면 인증+FDS 왕복이
     * 통째로 측정에서 빠지고, payment가 커밋 즉시 웹훅을 쏘기 때문에 응답보다 먼저 도착한
     * 웹훅이 갈 곳을 잃는다.
     */
    @PostMapping
    public ResponseEntity<String> requestPayment(@RequestBody String body) {
        String orderId = paymentFlow.begin(body);
        ResponseEntity<String> response = paymentClient.requestPayment(body);
        paymentFlow.onPaymentResponse(orderId, response);
        return response;
    }

    @PostMapping("/{paymentKey}/approve")
    public ResponseEntity<String> approvePayment(@PathVariable String paymentKey) {
        return paymentClient.approve(paymentKey);
    }

    @PostMapping("/{paymentKey}/capture")
    public ResponseEntity<String> capturePayment(@PathVariable String paymentKey) {
        return paymentClient.capture(paymentKey);
    }
}
