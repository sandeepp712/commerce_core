package com.commercecore.backend.payment.repo;

import com.commercecore.backend.payment.domain.PendingRefund;
import com.commercecore.backend.payment.domain.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PendingRefundRepository extends JpaRepository<PendingRefund, UUID> {

    /**
     * FOR UPDATE SKIP LOCKED prevents multiple worker instances
     * from processing the same refund simultaneously.
     * If worker A locks a row, worker B skips it and moves to the next.
     */
    @Query(value = """
        SELECT * FROM pending_refunds
        WHERE status = 'PENDING' AND retry_count < 3
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<PendingRefund> findPendingForProcessing(@Param("batchSize") int batchSize);
}