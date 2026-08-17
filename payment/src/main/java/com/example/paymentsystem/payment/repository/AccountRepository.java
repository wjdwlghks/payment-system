package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.merchantId = :merchantId")
    Optional<Account> findByAccountTypeAndMerchantId(
            @Param("type") AccountType type,
            @Param("merchantId") String merchantId);

    /**
     * 조회하면서 곧바로 X-lock을 건다. 인라인 기표 모드({@code LedgerBalanceMode.INLINE/SHARDED}) 전용.
     *
     * <p>락 없이 먼저 읽고 나중에 잠그면 안 된다 — 그 인스턴스가 영속성 컨텍스트에 남아,
     * 잠근 뒤에도 잔액은 잠그기 전에 읽은 값이라 그 사이 커밋된 다른 매입의 갱신을 덮어쓴다.
     * 읽기와 잠그기가 한 문장이어야 그 창이 사라진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.merchantId = :merchantId")
    Optional<Account> findByAccountTypeAndMerchantIdForUpdate(
            @Param("type") AccountType type,
            @Param("merchantId") String merchantId);

    // AccountBalanceFlusher 전용 — 잔액 갱신 시에만 락 사용
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
