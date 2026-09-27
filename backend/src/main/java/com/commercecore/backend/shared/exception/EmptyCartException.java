package com.commercecore.backend.shared.exception;

import com.commercecore.backend.order.domain.OrderState;

public class EmptyCartException extends RuntimeException {
    public EmptyCartException(String message) {
        super(message);
    }

    public EmptyCartException() {
        super("Cart is empty or already checked out");
    }

    public static class IllegalOrderTransitionException extends RuntimeException {

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
}