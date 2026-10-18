package com.commercecore.backend.cart.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CartItemResponse(
        UUID productId,
        String productName,
        Integer quantity,
        BigDecimal unitprice,
        BigDecimal lineTotal
) { }