package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.Account;
import com.example.paymentsystem.payment.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountTypeAndMerchantId(AccountType accountType, String merchantId);
}
