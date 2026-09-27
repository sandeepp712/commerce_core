package com.commercecore.backend.shared.exception;

public class IdempotencyConflictException  extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency key already used with a different payload: " + key);
    }
}