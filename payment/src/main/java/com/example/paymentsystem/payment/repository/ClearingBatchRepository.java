package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.ClearingBatch;
import com.example.paymentsystem.payment.domain.ClearingBatchStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClearingBatchRepository extends JpaRepository<ClearingBatch, Long> {

    Optional<ClearingBatch> findTop1ByStatusOrderByWindowEndDesc(ClearingBatchStatus status);

    Optional<ClearingBatch> findTop1ByBatchCodeStartingWithOrderByBatchCodeDesc(String batchCodePrefix);

    @Query("""
    select b
    from ClearingBatch b
    where b.status = :status
      and not exists (
          select 1 from SettlementRunItem item where item.clearingBatch = b
      )
    order by b.clearedAt asc
""")
    List<ClearingBatch> findOldestUnsettledClearedBatches(
            @Param("status") ClearingBatchStatus status,
            Pageable pageable
    );

    @Query("""
    select b
    from ClearingBatch b
    where b.status = :status
      and b.clearedAt >= :windowStart
      and b.clearedAt < :windowEnd
      and not exists (
          select 1 from SettlementRunItem item where item.clearingBatch = b
      )
    order by b.clearedAt asc
""")
    List<ClearingBatch> findUnsettledClearedBatches(
            @Param("status") ClearingBatchStatus status,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );
}
