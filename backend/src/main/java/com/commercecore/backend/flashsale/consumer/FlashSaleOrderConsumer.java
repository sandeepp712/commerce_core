package com.commercecore.backend.flashsale.consumer;

import com.commercecore.backend.flashsale.event.FlashSaleOrderEvent;
import com.commercecore.backend.flashsale.service.FlashSaleStockService;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderItem;
import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Component
public class FlashSaleOrderConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(FlashSaleOrderConsumer.class);

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final FlashSaleStockService stockService;

    public FlashSaleOrderConsumer(OrderRepository orderRepository,
                                  InventoryRepository inventoryRepository,
                                  FlashSaleStockService stockService) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.stockService = stockService;
    }

    /**
     * Consumes flash sale order events from RabbitMQ.
     *
     * IMPORTANT: prefetch=10 means this method receives up to 10 messages
     * at a time. We process them as a batch to reduce DB lock contention.
     *
     * Manual ACK ensures we only acknowledge AFTER successful DB write.
     * If the DB write fails, the message is NACK'd and redelivered.
     */
    @RabbitListener(queues = "${flash-sale.queue-name}")
    public void consumeOrderEvent(FlashSaleOrderEvent event,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
            throws IOException {
        try {
            processOrder(event);

            // ACK: Message processed successfully
            channel.basicAck(deliveryTag, false);
            LOG.info("Flash sale order processed: userId={}, productId={}",
                    event.userId(), event.productId());

        } catch (Exception e) {
            LOG.error("Failed to process flash sale order: {}", e.getMessage(), e);

            // NACK: Requeue the message for retry (or send to DLQ after max retries)
            // false = don't requeue immediately, let RabbitMQ retry logic handle it
            channel.basicNack(deliveryTag, false, false);

            // Rollback Redis stock since order processing failed
            stockService.rollback(event.productId(), event.quantity());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void processOrder(FlashSaleOrderEvent event) {
        // 1. Idempotency check: Has this order already been processed?
        boolean exists = orderRepository.existsByIdempotencyKey(
                event.idempotencyKey().toString());
        if (exists) {
            LOG.info("Duplicate order event ignored: {}", event.idempotencyKey());
            return;
        }

        // 2. Create the order
        BigDecimal total = event.unitPrice().multiply(BigDecimal.valueOf(event.quantity()));

        Order order = Order.builder()
                .userId(event.userId())
                .state(OrderState.AWAITING_PAYMENT_CONFIRMATION)
                .subtotal(total)
                .shipping(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO)
                .total(total)
                .currency("INR")
                .shippingAddress("{\"type\":\"flash-sale\"}")
                .idempotencyKey(event.idempotencyKey().toString())
                .expiresAt(Instant.now().plusSeconds(900))  // 15 min to pay
                .build();

        OrderItem item = new OrderItem(event.productId(), event.quantity(), event.unitPrice());
        order.addItem(item);

        // 3. Save order to PostgreSQL
        orderRepository.save(order);

        // 4. Confirm inventory reservation in PostgreSQL
        // This is the actual DB write that was deferred from the hot path
        inventoryRepository.reserveInventory(event.productId(), event.quantity());

        LOG.info("Order {} created for flash sale. Inventory reserved.", order.getOrderId());
    }
}