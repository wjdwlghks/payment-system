package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    public static final String GLOBAL_MERCHANT_ID = "GLOBAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 40)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_class", nullable = false, length = 20)
    private AccountClass accountClass;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(nullable = false)
    private Long balance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Account(AccountType accountType, AccountClass accountClass, String merchantId) {
        this.accountType = accountType;
        this.accountClass = accountClass;
        this.merchantId = merchantId;
        this.balance = 0L;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public long computeNetDelta(long debitSum, long creditSum) {
        return switch (accountClass) {
            case ASSET, EXPENSE -> debitSum - creditSum;
            case LIABILITY, REVENUE -> creditSum - debitSum;
        };
    }

    public void applyDelta(long delta) {
        this.balance += delta;
    }

    public void apply(LedgerDirection direction, Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        Long signedAmount = signedAmount(direction, amount);
        this.balance += signedAmount;
    }

    private Long signedAmount(LedgerDirection direction, Long amount) {
        return switch (accountClass) {
            case ASSET, EXPENSE -> direction == LedgerDirection.DEBIT ? amount : -amount;
            case LIABILITY, REVENUE -> direction == LedgerDirection.CREDIT ? amount : -amount;
        };
    }
}
