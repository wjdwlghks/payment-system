package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.component.FdsScheduler;
import com.example.paymentsystem.payment.component.InquiryScheduler;
import com.example.paymentsystem.payment.component.WebhookScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/scheduler")
@RequiredArgsConstructor
public class SchedulerAdminController {

    private final InquiryScheduler inquiryScheduler;
    private final FdsScheduler fdsScheduler;
    private final WebhookScheduler webhookScheduler;

    /**
     * 수렴을 앞당기는 관리용 트리거.
     *
     * <p>UNKNOWN 재조회는 이제 {@code InquiryQueue}가 예약 시각에 처리하므로 여기서 앞당길 수 없다 —
     * 대신 sweeper를 돌려 큐가 놓친 건이 있으면 즉시 회수한다. 지연을 측정하는 실행에서는
     * 이 엔드포인트를 부르면 안 된다. 복구에 걸린 시간이 아니라 호출 빈도를 재게 된다.
     */
    @PostMapping("/run-now")
    public void runNow() {
        inquiryScheduler.sweepLostUnknowns();
        inquiryScheduler.inquiryStaleRequested();
        inquiryScheduler.recoverStaleIdempotencyKeys();
        fdsScheduler.checkAuthenticatedPayment();
        webhookScheduler.webhook();
    }
}
