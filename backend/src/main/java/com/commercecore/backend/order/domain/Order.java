package com.commercecore.backend.order.domain;

public class Order {

    private OrderState state;

    /** Domain guard. Throws unless the edge is legal. */
    public void transitionTo(OrderState next) {
        OrderStateMachine.assertTransition(this.state, next);
        this.state = next;
    }

    public OrderState getState() { return state; }
}