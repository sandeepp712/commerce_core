package com.commercecore.backend.cart.service;

import com.commercecore.backend.cart.api.dto.AddCartItemRequest;
import com.commercecore.backend.cart.api.dto.CartItemResponse;
import com.commercecore.backend.cart.api.dto.CartResponse;
import com.commercecore.backend.cart.domain.Cart;
import com.commercecore.backend.cart.domain.CartItem;
import com.commercecore.backend.cart.domain.CartStatus;
import com.commercecore.backend.cart.repo.CartRepository;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.commercecore.backend.shared.exception.InsufficientStockException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public CartService(CartRepository cartRepository,
                       ProductRepository productRepository,
                       InventoryRepository inventoryRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public CartResponse getCart(UUID userId) {
        Cart cart = getOrCreateActiveCart(userId);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        // 1. Validate product exists and has stock
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));
        if (!product.getActive()) {
            throw new IllegalStateException("Product is not active");
        }

        int available = inventoryRepository.getAvailableStock(request.productId());
        if (available < request.quantity()) {
            throw new InsufficientStockException(request.productId());
        }

        // 2. Get or create cart
        Cart cart = getOrCreateActiveCart(userId);

        // 3. Add/update item (upsert logic)
        cart.addItem(request.productId(), request.quantity(), product.getPrice());
        cart = cartRepository.save(cart);

        return toResponse(cart);
    }

    @Transactional
    public CartResponse removeItem(UUID userId, UUID productId) {
        Cart cart = getOrCreateActiveCart(userId);
        cart.removeItem(productId);
        cart = cartRepository.save(cart);
        return toResponse(cart);
    }

    @Transactional
    public void clearCart(UUID userId) {
        cartRepository.findActiveByUserId(userId).ifPresent(cart -> {
            cart.clearItems();
            cartRepository.save(cart);
        });
    }

    private Cart getOrCreateActiveCart(UUID userId) {
        return cartRepository.findActiveByUserId(userId)
                .orElseGet(() -> {
                    Cart newCart = Cart.createNew(userId);  // You'll need this factory method
                    return cartRepository.save(newCart);
                });
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getCartItems().stream()
                .map(this::toItemResponse)
                .toList();

        BigDecimal subtotal = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(
                cart.getCartId(),
                cart.getUserId(),
                cart.getStatus().name(),
                items,
                subtotal,
                items.stream().mapToInt(CartItemResponse::quantity).sum()
        );
    }

    private CartItemResponse toItemResponse(CartItem item) {
        Product product = productRepository.findById(item.getProductId()).orElse(null);
        return new CartItemResponse(
                item.getProductId(),
                product != null ? product.getName() : "Unknown",
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal()
        );
    }
}