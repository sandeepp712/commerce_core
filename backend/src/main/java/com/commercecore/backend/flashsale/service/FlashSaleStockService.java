package com.commercecore.backend.flashsale.service;

import com.commercecore.backend.inventory.repo.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Service
public class FlashSaleStockService {
    private static final Logger LOG = LoggerFactory.getLogger(FlashSaleStockService.class);

    @Value("${flash-sale.redis-key-prefix}")
    private final StringRedisTemplate stringRedisTemplate;
    private final InventoryRepository inventoryRepository;


    private String stockKeyPrefix;

    /**
     * Lua script for atomic stock reservation.
     * This executes atomically in Redis — no race condition possible.
     *
     * Returns:
     *   >= 0: Remaining stock after reservation (SUCCESS)
     *   -1:   Product not found in cache
     *   -2:   Insufficient stock
     */
    private static final String RESERVE_STOCK_LUA = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then
                return -1
            end
            local qty = tonumber(ARGV[1])
            if stock < qty then
                return -2
            end
            redis.call('DECRBY', KEYS[1], qty)
            return stock - qty
            """;

    private final DefaultRedisScript<Long> reserveStockScript;

    public FlashSaleStockService(StringRedisTemplate stringRedisTemplate,
                                 InventoryRepository inventoryRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.inventoryRepository = inventoryRepository;
        this.reserveStockScript = new DefaultRedisScript<>();
        this.reserveStockScript.setScriptText(RESERVE_STOCK_LUA);
        this.reserveStockScript.setResultType(Long.class);
    }

    /**
     * Pre-warm: Load stock from PostgreSQL into Redis.
     * Call this BEFORE the flash sale starts.
     */
    public int preWarm(UUID productId) {
        Integer availableStock = inventoryRepository.getAvailableStock(productId);
        if (availableStock == null || availableStock <= 0) {
            throw new IllegalStateException("Cannot pre-warm product with no available stock");
        }

        String key = stockKeyPrefix + productId;
        stringRedisTemplate.opsForValue().set(key, String.valueOf(availableStock));
        stringRedisTemplate.expire(key, Duration.ofHours(2));  // Auto-expire after sale window

        LOG.info("Pre-warmed product {} with {} units in Redis", productId, availableStock);
        return availableStock;
    }

    /**
     * Atomically reserve stock in Redis.
     * Returns remaining stock if successful, negative value if failed.
     *
     * -1 = product not in cache (not pre-warmed)
     * -2 = insufficient stock (out of stock)
     * >= 0 = success (returns remaining stock)
     */
    public long tryReserve(UUID productId, int quantity) {
        String key = stockKeyPrefix + productId;
        Long result = stringRedisTemplate.execute(
                reserveStockScript,
                Collections.singletonList(key),
                String.valueOf(quantity)
        );
        return result != null ? result : -1;
    }

    /**
     * Rollback a reservation (e.g., if order processing fails downstream).
     */
    public void rollback(UUID productId, int quantity) {
        String key = stockKeyPrefix + productId;
        stringRedisTemplate.opsForValue().increment(key, quantity);
        LOG.warn("Rolled back {} units for product {}", quantity, productId);
    }

    /**
     * Get current stock from Redis (for monitoring/debugging).
     */
    public Integer getStock(UUID productId) {
        String key = stockKeyPrefix + productId;
        String value = stringRedisTemplate.opsForValue().get(key);
        return value != null ? Integer.parseInt(value) : null;
    }

    /**
     * Check if product is pre-warmed in Redis.
     */
    public boolean isPreWarmed(UUID productId) {
        String key = stockKeyPrefix + productId;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }
}