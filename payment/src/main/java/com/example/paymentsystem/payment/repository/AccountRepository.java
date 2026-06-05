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
    Optional<Account> findByAccountTypeAndMerchantId(AccountType accountType, String merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.merchantId = :merchantId")
    Optional<Account> findByAccountTypeAndMerchantIdForUpdate(
            @Param("type") AccountType type,
            @Param("merchantId") String merchantId);
}
