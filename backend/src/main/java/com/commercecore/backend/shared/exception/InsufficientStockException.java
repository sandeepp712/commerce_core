package com.commercecore.backend.shared.exception;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }

    // Helper constructor for direct Product ID usage
    public InsufficientStockException(UUID productId) {
        super("Insufficient stock available for product ID: " + productId);
    }

    // Helper constructor for Product ID + Requested Quantity
    public InsufficientStockException(UUID productId, int requestedQty) {
        super(String.format("Insufficient stock for product ID %s. Requested quantity: %d", productId, requestedQty));
    }
}