package com.commercecore.backend.flashsale.service;

import java.util.UUID;

public record FlashSaleCheckoutResponse(
        UUID orderId,
        UUID productId,
        int quantity,
        String status,
        String message
) { }