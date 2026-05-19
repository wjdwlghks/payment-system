package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "ledger_posting",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ledger_posting_source",
                columnNames = {"posting_type", "source_type", "source_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false, length = 30)
    private LedgerPostingType postingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private LedgerSourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;

    @Column(name = "total_debit", nullable = false)
    private Long totalDebit;

    @Column(name = "total_credit", nullable = false)
    private Long totalCredit;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    public LedgerPosting(
            LedgerPostingType postingType,
            LedgerSourceType sourceType,
            String sourceId,
            Long totalDebit,
            Long totalCredit
    ) {
        validateTotals(totalDebit, totalCredit);
        this.postingType = postingType;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    @PrePersist
    void prePersist() {
        this.postedAt = Instant.now();
    }

    private void validateTotals(Long totalDebit, Long totalCredit) {
        if (totalDebit <= 0 || totalCredit <= 0) {
            throw new IllegalArgumentException("posting totals must be positive");
        }
        if (!totalDebit.equals(totalCredit)) {
            throw new IllegalArgumentException("posting totals must be balanced");
        }
    }
}
