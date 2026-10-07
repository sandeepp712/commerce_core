package com.commercecore.backend.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID>{
    /**
     * Fetches a batch of unpublished events for the background poller.
     *
     * WHY NATIVE QUERY?
     * JPQL does not support the `SKIP LOCKED` clause. We must drop to native SQL.
     *
     * WHY FOR UPDATE SKIP LOCKED?
     * This is the secret to horizontal scalability of the poller.
     * - FOR UPDATE: Locks the selected rows so no other transaction can modify them.
     * - SKIP LOCKED: If another poller instance has already locked a row,
     *   this query simply skips it and grabs the next available row.
     *
     * Result: Multiple poller instances can run concurrently, safely dividing
     * the workload without duplicating events or blocking each other.
     */

    @Query(value = """
            SELECT * FROM outbox 
            WHERE published = false 
            ORDER BY created_at ASC 
            LIMIT :limit 
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatch(@Param("limit") int limit);


    /**
     * Bulk update to mark events as published.
     * Using a bulk @Modifying query is significantly faster than loading
     * 100 entities into the Hibernate Persistence Context and calling saveAll().
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.published = true, o.publishedAt = :publishedAt WHERE o.id IN :ids")
    int markAsPublished(@Param("ids") List<UUID> ids, @Param("publishedAt") Instant publishedAt);
}