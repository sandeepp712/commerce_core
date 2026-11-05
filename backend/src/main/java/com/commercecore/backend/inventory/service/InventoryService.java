package com.commercecore.backend.inventory.service;

import com.commercecore.backend.inventory.repo.InventoryRepository;
import io.lettuce.core.RedisException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final Logger LOG = LoggerFactory.getLogger(InventoryService.class);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private static final String STOCK_KEY_PREFIX = "stock:";

    /**
     * The Redis Pre-Filter.
     * Returns true if stock was successfully reserved in Redis.
     */
    public boolean tryReserveStock(UUID productId,int qty) {
        String key = STOCK_KEY_PREFIX + productId;

        try{
            Long newStock = redisTemplate.opsForValue().decrement(key,qty);

            if (newStock != null && newStock < 0) {
                // Compensate immediately
                Long rolledBack = redisTemplate.opsForValue().increment(key, qty); // FIXED: Capture the rolled-back value
                LOG.warn("Stock went negative for product {}. Rolled back to {}. Original decrement: {}",
                        productId, rolledBack, newStock);
                return false;
            }

            // Set TTL only if successful
            if (newStock != null && newStock >= 0) {
                redisTemplate.expire(key, RESERVATION_TTL);
            }
            return true;
        }catch (Exception e){
            LOG.warn("Redis operation failed reserve stock for product {}", productId, e);
            try {
                redisTemplate.opsForValue().increment(key, qty);
            }catch (Exception e1){
                LOG.warn("CRITICAL: Compensation also failed for product {}. Manual intervention may be required.", productId);
            }
            return false;
        }

    }

    /**
     * Compensating action if the Database transaction fails.
     */
    public void releaseReservedStock(UUID productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().increment(key, quantity);
    }
}