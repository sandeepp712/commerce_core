package com.commercecore.backend.catalog.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
        UUID productId,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String currency,
        Integer availableStock,
        Boolean active
) {
}