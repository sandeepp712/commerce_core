package com.commercecore.backend.inventory.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class InventoryService {
    private final Logger LOG = LoggerFactory.getLogger(InventoryService.class);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private static final String STOCK_KEY_PREFIX = "stock:";
    private final DefaultRedisScript<Long> redisScript;

    public InventoryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.redisScript = new DefaultRedisScript<>();
        this.redisScript.setLocation(new ClassPathResource("scripts/reserve.lua"));
        this.redisScript.setResultType(Long.class);
    }

    /**
     * The Redis Pre-Filter.
     * Returns true if stock was successfully reserved in Redis.
     */
    public boolean tryReserveStock(UUID productId,int qty) {
        String key = STOCK_KEY_PREFIX + productId;

        try{
            Long result = redisTemplate.execute(redisScript, List.of(key), String.valueOf(qty));

            return result != null && result == 1L;
        }catch (Exception e){
            LOG.warn("Redis failed for product {}. Letting DB handle it.", productId, e);
            return true;
        }
    }


    public void releaseReservedStock(UUID productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        try {
            redisTemplate.opsForValue().increment(key, quantity);
        } catch (Exception e) {
            LOG.error("CRITICAL: Failed to release Redis stock for product {}", productId, e);
        }
    }
}