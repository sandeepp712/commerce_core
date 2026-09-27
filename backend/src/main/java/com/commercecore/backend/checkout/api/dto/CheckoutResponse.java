package com.commercecore.backend.checkout.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutResponse(
        UUID orderId,
        String state,
        BigDecimal total,
        String currency
) { }