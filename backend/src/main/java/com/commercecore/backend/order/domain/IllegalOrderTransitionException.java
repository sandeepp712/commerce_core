package com.commercecore.backend.order.domain;

public class IllegalOrderTransitionException extends RuntimeException {

    private final OrderState from;
    private final OrderState to;

    public IllegalOrderTransitionException(OrderState from, OrderState to) {
        super("Illegal order transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderState getFrom() { return from; }
    public OrderState getTo()   { return to; }
}