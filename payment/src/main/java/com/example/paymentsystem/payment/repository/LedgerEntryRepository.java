package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query(value = """
        SELECT COUNT(*) FROM (
            SELECT posting_id
            FROM ledger_entry
            GROUP BY posting_id
            HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) != 0
        ) t
        """, nativeQuery = true)
    long countUnbalancedPostings();
}
