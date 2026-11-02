package com.commercecore.backend.inventory.service;

import com.commercecore.backend.inventory.repo.InventoryRepository;
import io.lettuce.core.RedisException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private final StringRedisTemplate redisTemplate;

    private static final String STOCK_KEY_PREFIX = "stock:";



    /**
     * The Redis Pre-Filter.
     * Returns true if stock was successfully reserved in Redis.
     */
    public boolean tryReserveStock(UUID productId,int qty) {
        String key = STOCK_KEY_PREFIX + productId;

        Long newStock = redisTemplate.opsForValue().decrement(key,qty);

        if (newStock != null && newStock >= 0) {
            redisTemplate.expire(key, 2, TimeUnit.MINUTES);
        }

        if (newStock != null && newStock < 0) {
            redisTemplate.opsForValue().increment(key, qty);
            return false;
        }

        return true;
    }

    /**
     * Compensating action if the Database transaction fails.
     */
    public void releaseReservedStock(UUID productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().increment(key, quantity);
    }
}