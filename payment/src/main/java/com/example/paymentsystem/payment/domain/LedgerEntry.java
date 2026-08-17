package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "posting_id", nullable = false)
    private LedgerPosting posting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerDirection direction;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private LedgerEntryType entryType;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(nullable = false)
    private boolean applied = false;

    public LedgerEntry(
            LedgerPosting posting,
            Account account,
            LedgerDirection direction,
            Long amount,
            LedgerEntryType entryType
    ) {
        validateAmount(amount);
        this.posting = posting;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.entryType = entryType;
    }

    public LedgerEntry(
            Account account,
            LedgerDirection direction,
            Long amount,
            LedgerEntryType entryType
    ) {
        this(null, account, direction, amount, entryType);
    }

    /**
     * 이 entry가 계정 잔액에 이미 반영됐다고 표시한다.
     *
     * <p>인라인 모드에서 쓴다 — 기표와 같은 트랜잭션에서 잔액을 올렸으므로,
     * 표시하지 않으면 {@code AccountBalanceFlusher}가 같은 금액을 한 번 더 더한다.
     */
    public void markApplied() {
        this.applied = true;
    }

    public void assignPosting(LedgerPosting posting) {
        if (this.posting != null) {
            throw new IllegalStateException("posting already assigned");
        }
        this.posting = posting;
    }

    @PrePersist
    void prePersist() {
        this.postedAt = Instant.now();
    }

    private void validateAmount(Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
