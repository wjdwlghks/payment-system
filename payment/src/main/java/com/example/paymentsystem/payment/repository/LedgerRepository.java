package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.domain.LedgerEntry;
import com.example.paymentsystem.payment.domain.LedgerEntryType;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    @Query("""
    select coalesce(sum(e.amount), 0L)
    from LedgerEntry e
    where e.account.id in :accountIds
      and e.entryType in :entryTypes
      and e.direction = :direction
      and e.postedAt >= :rangeStart
      and e.postedAt < :rangeEnd
""")
    long sumDirectionalAmount(
            @Param("accountIds") Collection<Long> accountIds,
            @Param("entryTypes") Collection<LedgerEntryType> entryTypes,
            @Param("direction") LedgerDirection direction,
            @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd
    );
}
