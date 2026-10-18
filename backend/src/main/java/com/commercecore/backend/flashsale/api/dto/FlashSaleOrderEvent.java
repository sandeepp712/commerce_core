package com.commercecore.backend.flashsale.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The message published to RabbitMQ when a flash sale reservation succeeds.
 * This is the "intent" to create an order. The consumer will process it
 * and write to PostgreSQL.
 */
public record FlashSaleOrderEvent(
        UUID userId,
        UUID productId,
        int quantity,
        BigDecimal unitprice,
        UUID idempotencyKey,
        Instant timestamp
) implements Serializable { }