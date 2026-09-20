package com.commercecore.backend.order;

import com.commercecore.backend.order.domain.IllegalOrderTransitionException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.domain.OrderStateMachine;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateMachineTest {

    @Test
    void exhaustiveCheck_all49Pairs() {
        int legal = 0, illegal = 0;

        for (OrderState from : OrderState.values()) {
            for (OrderState to : OrderState.values()) {
                boolean expected = OrderStateMachine.isLegal(from, to);

                if (expected) {
                    assertDoesNotThrow(
                            () -> OrderStateMachine.assertTransition(from, to),
                            "Expected LEGAL: " + from + " -> " + to);
                    legal++;
                } else {
                    IllegalOrderTransitionException ex = assertThrows(
                            IllegalOrderTransitionException.class,
                            () -> OrderStateMachine.assertTransition(from, to),
                            "Expected ILLEGAL: " + from + " -> " + to);
                    assertEquals(from, ex.getFrom(), "from in exception");
                    assertEquals(to, ex.getTo(), "to in exception");
                    illegal++;
                }
            }
        }

        assertEquals(8, legal, "legal edge count");
        assertEquals(41, illegal, "illegal pair count");
        assertEquals(49, OrderState.values().length * OrderState.values().length);
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (OrderState terminal : EnumSet.of(
                OrderState.CANCELLED, OrderState.EXPIRED, OrderState.REFUNDED)) {
            assertTrue(OrderStateMachine.legalTargets(terminal).isEmpty(),
                    terminal + " must be terminal");
        }
    }

    @Test
    void namedForbiddenEdges() {
        // Money: no double capture
        assertFalse(OrderStateMachine.isLegal(
                OrderState.PAID, OrderState.PAID));

        // Goods: no resurrection from a dead order
        assertFalse(OrderStateMachine.isLegal(
                OrderState.CANCELLED, OrderState.PAID));
        assertFalse(OrderStateMachine.isLegal(
                OrderState.EXPIRED, OrderState.PAID));

        // Goods in motion: no cancel after ship
        assertFalse(OrderStateMachine.isLegal(
                OrderState.SHIPPED, OrderState.CANCELLED));

        // Nothing to refund before money moves
        assertFalse(OrderStateMachine.isLegal(
                OrderState.AWAITING_PAYMENT_CONFIRMATION, OrderState.REFUNDED));

        // Cannot ship unpaid
        assertFalse(OrderStateMachine.isLegal(
                OrderState.AWAITING_PAYMENT_CONFIRMATION, OrderState.SHIPPED));

        // Cannot skip SHIPPED
        assertFalse(OrderStateMachine.isLegal(
                OrderState.PAID, OrderState.DELIVERED));

        // Pre-fulfilment is CANCELLED, not REFUNDED
        assertFalse(OrderStateMachine.isLegal(
                OrderState.PAID, OrderState.REFUNDED));
    }

    @Test
    void namedLegalEdges() {
        assertTrue(OrderStateMachine.isLegal(
                OrderState.AWAITING_PAYMENT_CONFIRMATION, OrderState.PAID));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.AWAITING_PAYMENT_CONFIRMATION, OrderState.EXPIRED));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.AWAITING_PAYMENT_CONFIRMATION, OrderState.CANCELLED));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.PAID, OrderState.SHIPPED));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.PAID, OrderState.CANCELLED));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.SHIPPED, OrderState.DELIVERED));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.SHIPPED, OrderState.REFUNDED));
        assertTrue(OrderStateMachine.isLegal(
                OrderState.DELIVERED, OrderState.REFUNDED));
    }
}