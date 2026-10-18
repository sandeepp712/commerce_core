package com.commercecore.backend.order.service;

import com.commercecore.backend.order.repo.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrderExpirationReaper {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpirationReaper.class);
    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderExpirationService orderExpirationService;

    public OrderExpirationReaper(OrderRepository orderRepository,
                                 OrderExpirationService orderExpirationService) {
        this.orderRepository = orderRepository;
        this.orderExpirationService = orderExpirationService;
    }

    /**
     * Runs every 30 seconds.
     *
     * DESIGN DECISIONS:
     * - fixedDelay (not fixedRate): Waits 30s AFTER the previous run completes.
     *   If a batch takes 10s to process, the next run starts at T+40s, not T+30s.
     *   This prevents overlapping executions if a batch is slow.
     *
     * - Each order is expired in its own transaction (via OrderExpirationService).
     *   The outer method is NOT @Transactional. Why?
     *   If it were, and order #50 failed, orders #1-49 would roll back too.
     *   We want partial success: expire what you can, skip what you can't.
     *
     * - One failure does NOT stop the batch.
     *   If order #50 throws an unexpected exception, we log it and continue
     *   to order #51. A single poison order must not halt the entire reaper.
     */
    @Scheduled(fixedDelay = 30_000)
    public void reapExpiredOrders() {
        List<UUID> expiredIds = fetchExpiredOrderIds();

        if (expiredIds.isEmpty()) {
            return;  // Nothing to do. Don't spam logs.
        }

        LOG.info("TTL Reaper: found {} expired orders to process", expiredIds.size());

        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;

        for (UUID orderId : expiredIds) {
            try {
                orderExpirationService.expireOrder(orderId);
                successCount++;
            } catch (Exception e) {
                // One failure must not stop the batch.
                // Log it, skip it, continue to the next order.
                failCount++;
                LOG.error("TTL Reaper: failed to expire order {}. Error: {}",
                        orderId, e.getMessage(), e);
            }
        }

        if (failCount > 0 || skipCount > 0) {
            LOG.warn("TTL Reaper batch complete: {} expired, {} skipped, {} failed",
                    successCount, skipCount, failCount);
        } else {
            LOG.info("TTL Reaper batch complete: {} orders expired", successCount);
        }
    }

    /**
     * Fetches expired order IDs using FOR UPDATE SKIP LOCKED.
     * This runs in its own short transaction (just the SELECT + lock).
     * The actual expiration work happens in separate per-order transactions.
     */
    @Transactional(readOnly = true)
    public List<UUID> fetchExpiredOrderIds() {
        return orderRepository.findExpiredOrderIds(Instant.now(), BATCH_SIZE);
    }
}