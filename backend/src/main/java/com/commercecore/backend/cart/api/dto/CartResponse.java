package com.commercecore.backend.cart.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID cartID,
        UUID userId,
        String status,
        List<CartItemResponse> items,
        BigDecimal subtotal,
        Integer itemCount
) { }