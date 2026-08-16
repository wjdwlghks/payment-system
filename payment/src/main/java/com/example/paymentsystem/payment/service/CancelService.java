package com.example.paymentsystem.payment.service;

import com.example.paymentsystem.payment.client.card.CardCancelRequest;
import com.example.paymentsystem.payment.client.card.CardCancelResponse;
import com.example.paymentsystem.payment.client.card.CardClient;
import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.domain.IdempotentKeys;
import com.example.paymentsystem.payment.domain.IdempotentStatus;
import com.example.paymentsystem.payment.domain.PaymentIntent;
import com.example.paymentsystem.payment.domain.PaymentTransaction;
import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.dto.PaymentResponse;
import com.example.paymentsystem.payment.exception.PaymentValidationException;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import com.example.paymentsystem.payment.repository.PaymentIntentRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 승인취소 — 매입 전 승인을 되돌린다.
 *
 * <h2>이 클래스만 외부 호출을 트랜잭션 안에서 한다</h2>
 *
 * 나머지 단계(인증·FDS·승인·매입)는 카드사 호출을 트랜잭션 밖에 두고, 응답을 못 받으면
 * UNKNOWN으로 적어둔 뒤 조회로 확정한다. 취소는 그 구조를 쓰지 않는다 —
 * <b>확정 응답만 결과로 인정하고, 그 외에는 전부 롤백한다.</b>
 *
 * <p>그래서 예외가 나면 멱등키·CANCEL 트랜잭션·intent 상태가 통째로 사라지고, 가맹점은
 * 그냥 다시 호출하면 된다. UNKNOWN 상태도, 조회 경로도, 취소가 실패한 채 굳는 상태도
 * 생기지 않는다. 카드사 쪽도 같은 전제로 재요청 시 첫 취소를 그대로 재생한다.
 *
 * <p>대가는 HTTP 왕복 동안 DB 커넥션을 쥐고 있다는 것이다. 취소는 저빈도라 감당되지만
 * 결제 본류에는 절대 쓰면 안 되는 방식이다.
 *
 * <h2>원장을 건드리지 않는 이유</h2>
 *
 * 원장은 매입 시점에만 기표된다. 매입 전 취소에는 되돌릴 회계 기록이 아예 없다.
 * 매입 이후는 이 경로가 거부하고 환불이 담당한다(환불은 아직 미구현).
 */
@Service
@RequiredArgsConstructor
public class CancelService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IdempotentService idempotentService;
    private final WebhookService webhookService;
    private final CardClient cardClient;
    private final ObjectMapper objectMapper;

    @Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 3)
    @Transactional
    public PaymentResponse cancel(String paymentKey) {
        PaymentIntent paymentIntent = paymentIntentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + paymentKey));

        // 멱등키 삽입이 상태 가드보다 먼저다 — 승인·매입과 같은 순서다.
        // 반대로 두면 이미 취소된 결제의 재요청이 422에 먼저 걸려 저장된 응답을 replay할 수 없다.
        IdempotencyKey key = IdempotencyKey.builder()
                .idempotentKey(IdempotentKeys.paymentCancel(paymentIntent.getMerchantId(), paymentKey))
                .operation(IdempotencyOperation.PAYMENT_CANCEL)
                .status(IdempotentStatus.PROCESSING)
                .build();
        idempotencyKeyRepository.saveAndFlush(key);

        // 아래 검증에서 던지는 예외는 전부 이 트랜잭션을 롤백시키므로 방금 넣은 키도 함께 사라진다.
        requireNotCaptured(paymentIntent);
        if (!paymentIntent.isCancelable()) {
            throw new PaymentValidationException(422, "Payment not cancelable: status is " + paymentIntent.getStatus());
        }

        PaymentTransaction cancelTransaction = paymentTransactionRepository.save(
                new PaymentTransaction(paymentIntent, TransactionType.CANCEL, paymentIntent.getAmount())
        );

        CardCancelResponse response = cardClient.cancel(
                paymentIntent.getCardCompany(),
                paymentIntent.getApprovalId(),
                new CardCancelRequest(cancelTransaction.getCardRequestRef(), paymentIntent.getAmount())
        );
        // 카드사가 200 본문으로 실패를 알린 경우. 4xx·타임아웃은 예외로 여기까지 오지 않는다.
        if (!response.success()) {
            throw new PaymentValidationException(422, "Card company rejected the cancel");
        }

        paymentIntent.markCanceled();
        cancelTransaction.markSucceeded(response.externalId());
        webhookService.saveCanceled(paymentIntent);

        PaymentResponse result = toResponse(paymentIntent);
        idempotentService.complete(
                key.getIdempotentKey(),
                IdempotencyOperation.PAYMENT_CANCEL,
                200,
                toJson(result)
        );
        return result;
    }

    /**
     * 매입 진행 상태로 취소 가능 여부를 가른다.
     *
     * <p>{@code PaymentIntent.status}는 매입을 표현하지 않으므로(매입은 CAPTURE tx로만
     * 추적된다) 여기서 tx를 직접 봐야 한다.
     *
     * <ul>
     *   <li>tx 없음 — 매입을 요청한 적이 없다 → 취소 진행
     *   <li>{@code FAIL} — 매입이 확정 실패했고 재시도 경로도 없다(결제당 CAPTURE tx는
     *       하나뿐). 승인만 살아 있는 상태라 취소가 유일한 출구다 → 취소 진행
     *   <li>{@code REQUESTED}/{@code UNKNOWN} — 매입 여부를 모른다. 여기서 취소를 보내면
     *       이미 매입된 건을 되돌리려 들 수 있다 → 409, 조회가 확정한 뒤 다시 부르게 한다
     *   <li>{@code SUCCEEDED} — 대금이 청구됐다 → 422, 환불의 영역
     * </ul>
     */
    private void requireNotCaptured(PaymentIntent paymentIntent) {
        Optional<PaymentTransaction> capture = paymentTransactionRepository
                .findByPaymentIntentAndType(paymentIntent, TransactionType.CAPTURE);
        if (capture.isEmpty()) {
            return;
        }

        TransactionStatus status = capture.get().getStatus();
        switch (status) {
            case FAIL -> { }
            case REQUESTED, UNKNOWN -> throw new PaymentValidationException(
                    409, "Processing capture; retry cancel once it settles");
            case SUCCEEDED -> throw new PaymentValidationException(
                    422, "Already captured; refund required");
        }
    }

    private PaymentResponse toResponse(PaymentIntent paymentIntent) {
        return new PaymentResponse(
                paymentIntent.getPaymentKey(),
                paymentIntent.getOrderId(),
                paymentIntent.getStatus(),
                paymentIntent.getAmount(),
                paymentIntent.getAuthenticatedAt()
        );
    }

    private String toJson(PaymentResponse response) {
        return objectMapper.writeValueAsString(response);
    }
}
