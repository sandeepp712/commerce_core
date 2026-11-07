package com.commercecore.backend.checkout.service;

import com.commercecore.backend.cart.domain.Cart;
import com.commercecore.backend.cart.domain.CartItem;
import com.commercecore.backend.cart.repo.CartRepository;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.checkout.api.dto.CheckoutRequest;
import com.commercecore.backend.checkout.api.dto.CheckoutResponse;
import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.commercecore.backend.inventory.service.InventoryService;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderItem;
import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.shared.exception.EmptyCartException;
import com.commercecore.backend.shared.exception.IdempotencyConflictException;
import com.commercecore.backend.shared.exception.InsufficientStockException;
import com.commercecore.backend.shared.exception.ProductUnavailableException;
import com.commercecore.backend.shared.metrics.CheckoutMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
public class CheckoutService {
    private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final InventoryService inventoryService;
    private final CheckoutMetrics checkoutMetrics;
    public final InventoryRepository inventoryRepository;
    public final CartRepository cartRepository;
    public final OrderRepository orderRepository;
    public final ProductRepository productRepository;
    public final CheckoutWriter checkoutWriter;


    public CheckoutService(InventoryService inventoryService,InventoryRepository inventoryRepository, CartRepository cartRepository, OrderRepository orderRepository, ProductRepository productRepository, CheckoutMetrics checkoutMetrics,CheckoutWriter checkoutWriter) {
        this.inventoryRepository = inventoryRepository;
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.checkoutMetrics = checkoutMetrics;
        this.inventoryService = inventoryService;
        this.checkoutWriter = checkoutWriter;
    }

    /**
     * POST /checkout — creates the order in AWAITING_PAYMENT_CONFIRMATION,
     * reserves every line, marks the cart CHECKED_OUT.
     * <p>
     * All-or-nothing: any failure rolls back the whole method, including
     * every reservation already committed inside this transaction.
     */


    public CheckoutResponse processCheckout(UUID userId,String idempotencyKey ,CheckoutRequest req) {

        long startTime = System.currentTimeMillis();

        // 1. idempotency Check
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Order o = existing.get();
            if (!sameRequestHash(o, req, userId)) {
                throw new IdempotencyConflictException("IdempotencyKey already exists");
            }
            return toResponse(o);
        }

        Cart cart = cartRepository.findActiveByUserId(userId)
                .orElseThrow(EmptyCartException::new);

        if(cart.getCartItems().isEmpty()){
            throw new EmptyCartException("Cart is empty for user " + userId);
        }


        Optional<Order> pendingOrder = orderRepository.findByCartIdAndStateIn(
                cart.getCartId(),
                List.of(OrderState.AWAITING_PAYMENT_CONFIRMATION)
        );

        if(pendingOrder.isPresent()){
            LOG.info("Pending order exists for cart {},returning existing order",cart.getCartId());
            return toResponse(pendingOrder.get());
        }

        // 2. Build order items
        List<UUID> productIds = cart.getCartItems().stream()
                .map(CartItem::getProductId)
                .toList();

        // Batch fetch (1 SQL Query, no N+1 problem)
        Map<UUID, Product> productCatalog = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for(CartItem item : cart.getCartItems()) {
            Product product = productCatalog.get(item.getProductId());

            if (product == null || !product.getActive()) {
                throw new ProductUnavailableException("Product is no longer available: "+ item.getProductId());
            }

            BigDecimal liveUnitPrice = product.getPrice();

            OrderItem orderItem = new OrderItem(product.getProductId(),item.getQuantity(),liveUnitPrice);
            orderItems.add(orderItem);

            subtotal = subtotal.add(orderItem.getLineTotal());
        }

        // 3. Redis Pre filter
        List<OrderItem> reservedInRedis = new ArrayList<>();
        try{
            for(OrderItem orderItem : orderItems) {
                boolean reserved = inventoryService.tryReserveStock(orderItem.getProductId(),orderItem.getQty());
                if(!reserved) {
                    throw new InsufficientStockException("Insufficient stock for cart");
                }
                reservedInRedis.add(orderItem);
            }
        }catch (Exception e){
            compensateRedis(reservedInRedis);
            checkoutMetrics.recordInsufficientStock();
            throw e;
        }


        // 4. DB transaction
        BigDecimal shippingCost = BigDecimal.ZERO;
        BigDecimal tax = subtotal.multiply(new BigDecimal("0.05"));
        BigDecimal total = subtotal.add(shippingCost).add(tax);

        Instant expiration = Instant.now().plus(RESERVATION_TTL);
        String shippingAddressJson = String.format("{\"addressId\":\"%s\"}", req.shippingAddressId());

        Order order = Order.builder()
                .userId(userId)
                .cartId(cart.getCartId())
                .state(OrderState.AWAITING_PAYMENT_CONFIRMATION)
                .subtotal(subtotal)
                .shipping(shippingCost)
                .tax(tax)
                .total(total)
                .currency("INR")
                .shippingAddress(shippingAddressJson)
                .idempotencyKey(idempotencyKey)
                .expiresAt(expiration)
                .build();


        for (OrderItem orderItem : orderItems) {
            order.addItem(orderItem);
        }


        Order savedOrder;
        try {
            savedOrder = checkoutWriter.executeWrite(order,orderItems);
        }catch (DataIntegrityViolationException e) {
            compensateRedis(orderItems);
            Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(()-> new IllegalStateException("Idempotency constraint violated but key not found"));

            if(!sameRequestHash(existingOrder,req,userId)){
                throw new IdempotencyConflictException("IdempotencyKey already exists");
            }

            checkoutMetrics.recordDuplicateWebhook();
            return toResponse(existingOrder);
        }catch (Exception e) {
            compensateRedis(orderItems);
            throw e;
        }

        checkoutMetrics.recordSuccess(System.currentTimeMillis()-startTime);
        return toResponse(savedOrder);
    }



    private boolean sameRequestHash(Order order, CheckoutRequest req, UUID userId) {
        return order.getUserId().equals(userId);
    }

    private CheckoutResponse toResponse(Order order) {
        return new  CheckoutResponse(
                order.getOrderId(),
                order.getState().name(),
                order.getTotal(),
                order.getCurrency()
        );
    }

    private void compensateRedis(List<OrderItem> items) {
        for (OrderItem item : items) {
            inventoryService.releaseReservedStock(item.getProductId(), item.getQty());
        }
    }
}