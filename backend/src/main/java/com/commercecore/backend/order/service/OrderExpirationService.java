package com.commercecore.backend.order.service;

import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderEvent;
import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.repo.OrderEventRepository;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.shared.outbox.OutboxEvent;
import com.commercecore.backend.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderExpirationService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpirationService.class);

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderEventRepository orderEventRepository;
    private final OutboxRepository outboxRepository;

    public OrderExpirationService(OrderRepository orderRepository,
                                  InventoryRepository inventoryRepository,
                                  OrderEventRepository orderEventRepository,
                                  OutboxRepository outboxRepository) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderEventRepository = orderEventRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Expires a single order and releases its inventory.
     *
     * CRITICAL: Each order is expired in its OWN transaction.
     * Why? If order #50 out of 100 fails (e.g., a constraint violation),
     * we don't want to roll back the successful expiration of orders #1-49.
     * One failure must not poison the entire batch.
     *
     * RACE CONDITION HANDLING:
     * Between the time the Reaper SELECTs this order and this method executes,
     * the payment webhook might have already transitioned it to PAID.
     * The state machine guard handles this:
     *   order.transitionTo(EXPIRED) on a PAID order → throws IllegalOrderTransitionException
     * We catch it, log it, and skip. No harm done.
     */
    @Transactional(rollbackFor = Exception.class)
    public void expireOrder(UUID orderId) {

        // 1. Load the order
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            LOG.warn("Order {} not found during expiration. Skipping.", orderId);
            return;
        }

        // 2. Attempt state transition (the state machine is the guard)
        try {
            order.transitionTo(OrderState.EXPIRED);
        } catch (Exception e) {
            // Race condition: the webhook already transitioned this order.
            // The state machine rejected EXPIRED because the order is now PAID.
            // This is CORRECT behavior. The payment won the race. Skip.
            LOG.info("Order {} skipped by Reaper: state is now {} (payment webhook won the race)",
                    orderId, order.getState());
            return;
        }

        // 3. Persist the state change
        orderRepository.save(order);

        // 4. Release inventory reservations
        // Uses your CTE: HELD → RELEASED, reserved -= qty
        inventoryRepository.releaseAllReservationsForOrder(orderId);

        // 5. Write order event (audit trail — NOT silent)
        orderEventRepository.save(new OrderEvent(
                orderId,
                "OrderExpired",
                "AWAITING_PAYMENT_CONFIRMATION",
                "EXPIRED",
                "{\"reason\":\"payment_timeout\",\"ttl_minutes\":15}"
        ));

        // 6. Write outbox event (notify customer: "Your order expired")
        outboxRepository.save(new OutboxEvent(
                "Order",
                orderId,
                "OrderExpired",
                String.format("{\"orderId\":\"%s\",\"userId\":\"%s\",\"reason\":\"payment_timeout\"}",
                        orderId, order.getUserId())
        ));

        LOG.info("Order {} expired. Inventory released. Outbox written.", orderId);
    }
}