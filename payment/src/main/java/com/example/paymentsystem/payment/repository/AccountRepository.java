package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.merchantId = :merchantId AND a.bucketIndex = 0")
    Optional<Account> findByAccountTypeAndMerchantId(
            @Param("type") AccountType type,
            @Param("merchantId") String merchantId);

    // 단일 bucket (non-sharded 또는 특정 bucket) 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.merchantId = :merchantId AND a.bucketIndex = :bucket")
    Optional<Account> findByAccountTypeAndMerchantIdAndBucketIndexForUpdate(
            @Param("type") AccountType type,
            @Param("merchantId") String merchantId,
            @Param("bucket") int bucket);

    // CARD_NETWORK_RECEIVABLE 전체 bucket 조회 (carry-over, verify용)
    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.merchantId = :merchantId ORDER BY a.bucketIndex")
    List<Account> findAllByAccountTypeAndMerchantId(
            @Param("type") AccountType type,
            @Param("merchantId") String merchantId);
}
