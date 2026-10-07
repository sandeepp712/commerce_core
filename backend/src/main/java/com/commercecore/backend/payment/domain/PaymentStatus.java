package com.commercecore.backend.payment.domain;

public enum PaymentStatus {
    CREATED,
    REQUIRES_ACTION,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    REFUNDED
}