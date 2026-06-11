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

    // AccountBalanceFlusher 전용 — 잔액 갱신 시에만 락 사용
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
