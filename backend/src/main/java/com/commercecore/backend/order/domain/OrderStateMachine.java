package com.commercecore.backend.order.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order state machine, as executable law.
 *
 * Legal edges:
 *   AWAITING_PAYMENT_CONFIRMATION -> PAID | CANCELLED | EXPIRED
 *   PAID                          -> SHIPPED | CANCELLED
 *   SHIPPED                       -> DELIVERED | REFUNDED
 *   DELIVERED                     -> REFUNDED
 *   CANCELLED, EXPIRED, REFUNDED  -> terminal
 *
 * Deliberately excluded edges and why:
 *   AWAITING_PAYMENT_CONFIRMATION -> REFUNDED : nothing to refund, no money moved
 *   PAID -> PAID                               : double-capture; second webhook must no-op
 *   CANCELLED -> PAID                          : stock was released; a dead order never resurrects
 *   EXPIRED -> PAID                            : same; late webhook must auto-refund, not resurrect
 *   SHIPPED -> CANCELLED                       : goods in motion; use the return path
 *   PAID -> REFUNDED                           : pre-fulfilment is cancel; refund is compensation
 *   PAID -> DELIVERED                          : cannot skip SHIPPED
 */
public final class OrderStateMachine {

    private static final Map<OrderState, Set<OrderState>> LEGAL = build();

    private OrderStateMachine() {}

    private static Map<OrderState, Set<OrderState>> build() {
        EnumMap<OrderState, Set<OrderState>> m = new EnumMap<>(OrderState.class);
        m.put(OrderState.AWAITING_PAYMENT_CONFIRMATION,
                EnumSet.of(OrderState.PAID, OrderState.CANCELLED, OrderState.EXPIRED));
        m.put(OrderState.PAID,
                EnumSet.of(OrderState.SHIPPED, OrderState.CANCELLED));
        m.put(OrderState.SHIPPED,
                EnumSet.of(OrderState.DELIVERED, OrderState.REFUNDED));
        m.put(OrderState.DELIVERED,
                EnumSet.of(OrderState.REFUNDED));
        m.put(OrderState.CANCELLED, EnumSet.noneOf(OrderState.class));
        m.put(OrderState.EXPIRED,   EnumSet.noneOf(OrderState.class));
        m.put(OrderState.REFUNDED,  EnumSet.noneOf(OrderState.class));
        return Collections.unmodifiableMap(m);
    }

    public static boolean isLegal(OrderState from, OrderState to) {
        return LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<OrderState> legalTargets(OrderState from) {
        return LEGAL.getOrDefault(from, Set.of());
    }

    public static void assertTransition(OrderState from, OrderState to) {
        if (!isLegal(from, to)) {
            throw new IllegalOrderTransitionException(from, to);
        }
    }
}