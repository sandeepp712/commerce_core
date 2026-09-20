package com.commercecore.backend.order.domain;

public enum OrderState {
    AWAITING_PAYMENT_CONFIRMATION,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    EXPIRED,
    REFUNDED
}
