package com.commercecore.backend.checkout.service;

import com.commercecore.backend.cart.domain.Cart;
import com.commercecore.backend.cart.domain.CartItem;
import com.commercecore.backend.cart.repo.CartRepository;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.checkout.api.dto.CheckoutRequest;
import com.commercecore.backend.checkout.api.dto.CheckoutResponse;
import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderItem;
import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.shared.exception.EmptyCartException;
import com.commercecore.backend.shared.exception.IdempotencyConflictException;
import com.commercecore.backend.shared.exception.InsufficientStockException;
import com.commercecore.backend.shared.exception.ProductUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
public class CheckoutService {
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    public final InventoryRepository inventoryRepository;
    public final CartRepository cartRepository;
    public final OrderRepository orderRepository;
    public final ProductRepository productRepository;


    public CheckoutService(InventoryRepository inventoryRepository, CartRepository cartRepository, OrderRepository orderRepository, ProductRepository productRepository) {
        this.inventoryRepository = inventoryRepository;
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;

    }

    /**
     * POST /checkout — creates the order in AWAITING_PAYMENT_CONFIRMATION,
     * reserves every line, marks the cart CHECKED_OUT.
     * <p>
     * All-or-nothing: any failure rolls back the whole method, including
     * every reservation already committed inside this transaction.
     */


    @Transactional(rollbackFor = Exception.class)
    public CheckoutResponse processCheckout(UUID userId,String idempotencyKey ,CheckoutRequest req) {

        // check idempotencykey was already user?
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Order o = existing.get();
            if (!sameRequestHash(o, req, userId)) {
                throw new IdempotencyConflictException("IdempotencyKey already exists");
            }
            return toResponse(o);
        }

        // Race path: 2 concurrent calls with same idempotency key
        Cart cart = cartRepository.findActiveByUserId(userId)
                .orElseThrow(EmptyCartException::new);

        if(cart.isEmpty()){
            throw new EmptyCartException("Cart is empty for user " + userId);
        }

        // 1. Extract item from the cart
        List<UUID> productIds = cart.getCartItems().stream()
                .map(CartItem::getProductId)
                .toList();

        // 2. Batch fetch (1 SQL Query, no N+1 problem)
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

        BigDecimal shippingCost = BigDecimal.ZERO;
        BigDecimal tax = subtotal.multiply(new BigDecimal("0.05"));
        BigDecimal total = subtotal.add(shippingCost).add(tax);

        Instant expiration = Instant.now().plus(RESERVATION_TTL);
        String shippingAddressJson = String.format("{\"addressId\":\"%s\"}", req.shippingAddressId());

        Order order = Order.builder()
                .userId(userId)
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


        try {
            orderRepository.saveAndFlush(order);
        }catch (DataIntegrityViolationException e) {
            Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(()-> new IllegalStateException("Idempotency constraint violated but key not found"));

            if(!sameRequestHash(existingOrder,req,userId)){
                throw new IdempotencyConflictException("IdempotencyKey already exists");
            }
            return toResponse(existingOrder);
        }


        // Inventory Reservation
        for(OrderItem item: order.getOrderItems()){
            int rowReserved = inventoryRepository.reserveInventory(item.getProductId(),item.getQty());
            if(rowReserved == 0){
                // Throwing this RuntimeException triggers @Transactional to ROLL BACK everything
                // above (Order, OrderItems). The stock remains untouched
                throw new InsufficientStockException(item.getProductId());
            }

            // Record hold with TTL
            inventoryRepository.insertReservation(order.getOrderId(),item.getProductId(), item.getQty(),expiration);
        }


        cart.markAsCheckedOut();
        cartRepository.save(cart);

        return toResponse(order);
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
}