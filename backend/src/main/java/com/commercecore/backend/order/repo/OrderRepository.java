package com.commercecore.backend.order.repo;

import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

     Optional<Order> findByIdempotencyKey(String idempotencyKey);

     boolean existsByIdempotencyKey(String idempotencyKey);

     List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

     /** Find expired orders that need reaping
      * FOR UPDATE SKIP LOCked: Locks selected row so no other transaction touches them
      * Allows multiple instance to run the reaper concurrently with breaking the each other or waiting to queue.
      *
      * @param now
      * @param batchSize
      * @return
      */
     @Query(value = """
            SELECT id FROM orders
            WHERE state = 'AWAITING_PAYMENT_CONFIRMATION'
              AND expires_at < :now
            ORDER BY expires_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
     List<UUID> findExpiredOrderIds(@Param("now") Instant now, @Param("batchSize") int batchSize);
}