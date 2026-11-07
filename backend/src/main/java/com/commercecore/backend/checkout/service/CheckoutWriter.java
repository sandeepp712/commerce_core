package com.commercecore.backend.checkout.service;

import com.commercecore.backend.cart.domain.Cart;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderItem;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.commercecore.backend.shared.exception.InsufficientStockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.Duration;

@Service
public class CheckoutWriter {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    public CheckoutWriter(OrderRepository orderRepository, InventoryRepository inventoryRepository) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
    }

    // THIS is the only place a DB connection is held, and only for a few milliseconds
    @Transactional(rollbackFor = Exception.class)
    public Order executeWrite(Order order, java.util.List<OrderItem> orderItems) {
        orderRepository.saveAndFlush(order);

        for (OrderItem item : orderItems) {
            int rowReserved = inventoryRepository.reserveInventory(item.getProductId(), item.getQty());
            if (rowReserved == 0) {
                throw new InsufficientStockException("DB Insufficient stock"); // Triggers rollback
            }
            inventoryRepository.insertReservation(order.getOrderId(),
                    item.getProductId(), item.getQty(),
                    Instant.now().plus(RESERVATION_TTL));
        }

        return order;
    }



}