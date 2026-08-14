package com.example.paymentsystem.merchant.controller;

import com.example.paymentsystem.merchant.component.PaymentFlow;
import com.example.paymentsystem.merchant.dto.PaymentWebhookRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/payment")
public class PaymentWebhookController {

    private final PaymentFlow paymentFlow;

    public PaymentWebhookController(PaymentFlow paymentFlow) {
        this.paymentFlow = paymentFlow;
    }

    /**
     * 접수만 하고 즉시 204. 실제 처리는 {@link PaymentFlow#enqueueWebhook}이 워커로 넘긴다 —
     * 이유는 그쪽 주석 참고(여기서 블로킹하면 payment의 스케줄러 스레드가 함께 멈춘다).
     *
     * <p>중복 이벤트도 반드시 204를 돌려줘야 한다. 그러지 않으면 payment가 계속 재시도한다.
     *
     * <p>처리 전에 ACK하므로, merchant가 ACK 직후 죽으면 그 이벤트는 유실된다.
     * 실서비스라면 "저장 → ACK → 비동기 처리" 순서여야 하지만, merchant는 DB가 없는
     * 부하 하네스라 인메모리 큐잉으로 둔 의식적인 타협이다.
     */
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody PaymentWebhookRequest request) {
        paymentFlow.enqueueWebhook(request);
        return ResponseEntity.noContent().build();
    }
}
