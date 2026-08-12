package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.domain.IdempotencyKey;
import com.example.paymentsystem.payment.domain.IdempotencyOperation;
import com.example.paymentsystem.payment.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class IdempotentService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * propagation은 기본값(REQUIRED)이다 — 호출자의 트랜잭션에 참여해야 한다.
     * 상태 전이(PaymentIntent/PaymentTransaction/webhook outbox)와 같은 커밋에 묶여야
     * "상태는 종료됐는데 멱등키는 PROCESSING"인 크래시 갭이 생기지 않는다.
     */
    @Transactional
    public void complete(String idempotentKey, IdempotencyOperation operation, int code, String responseBody) {
        IdempotencyKey key = idempotencyKeyRepository.findByIdempotentKeyAndOperation(idempotentKey, operation)
                .orElseThrow(() -> new IllegalStateException("idempotency key not found"));

        key.complete(code, responseBody);
    }

    // 병합된 트랜잭션이 유니크 제약 위반으로 롤백된 뒤, 이미 존재하는 키를 다시 조회할 때 사용
    public Optional<IdempotencyKey> find(String idempotentKey, IdempotencyOperation operation) {
        return idempotencyKeyRepository.findByIdempotentKeyAndOperation(idempotentKey, operation);
    }
}
