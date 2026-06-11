package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.AccountType;
import com.example.paymentsystem.payment.domain.LedgerDirection;
import com.example.paymentsystem.payment.domain.LedgerEntry;
import com.example.paymentsystem.payment.domain.LedgerEntryType;
import com.example.paymentsystem.payment.domain.LedgerPostingType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("select coalesce(sum(e.amount), 0L) from LedgerEntry e where e.account.id = :accountId and e.direction = :direction and e.applied = false")
    long sumUnappliedByDirection(
            @Param("accountId") Long accountId,
            @Param("direction") LedgerDirection direction
    );

    @Query("select distinct e.account.id from LedgerEntry e where e.applied = false")
    List<Long> findAccountIdsWithUnapplied();

    @Modifying
    @Query("update LedgerEntry e set e.applied = true where e.account.id = :accountId and e.applied = false")
    void markApplied(@Param("accountId") Long accountId);

    @Query("""
    select coalesce(sum(e.amount), 0L) from LedgerEntry e
    where e.posting.postingType = :postingType
      and e.posting.sourceId = :sourceId
      and e.account.accountType = :accountType
      and e.direction = :direction
""")
    long findEntryAmountByPostingAndAccount(
            @Param("postingType") LedgerPostingType postingType,
            @Param("sourceId") String sourceId,
            @Param("accountType") AccountType accountType,
            @Param("direction") LedgerDirection direction
    );
}
