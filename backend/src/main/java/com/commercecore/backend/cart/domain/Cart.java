package com.commercecore.backend.cart.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "cart")
public class Cart {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID cartId;

    @Column(name="user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable = false)
    private CartStatus status = CartStatus.ACTIVE;

    @Column(name = "created_at",nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at",nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "checkout_at")
    private Instant checkoutAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> cartItems = new ArrayList<>();

    protected Cart(){}

    public Cart(UUID cartId, UUID userId, CartStatus status,Instant updatedAt,List<CartItem> cartItems) {
        this.cartId = cartId;
        this.userId = userId;
        this.status = status;
        this.updatedAt = updatedAt;
        this.cartItems = cartItems;
    }


    public UUID getCartId() { return cartId; }
    public UUID getUserId() { return userId; }
    public CartStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCheckoutAt() { return checkoutAt; }
    public List<CartItem> getCartItems() { return Collections.unmodifiableList(cartItems); }

    public CartStatus setStatus(CartStatus status) { this.status = status; return this.status; }

    public void addItem(UUID productId, int quantity, BigDecimal unitPrice){
        if(quantity <= 0){
            throw new IllegalArgumentException("Quantity must be greater than zero.");
        }
        if(unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Unit price must be greater than zero.");
        }

        cartItems.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst()
                .ifPresentOrElse(
                        existingItem -> existingItem.incrementQuantity(quantity),
                        () -> {
                            CartItem newItem = new CartItem(productId, quantity, unitPrice);
                            newItem.setCart(this);
                            cartItems.add(newItem);
                        }
                );
        this.updatedAt = Instant.now();
    }

    public void markAsCheckedOut() {
        if(this.status == CartStatus.CHECKED_OUT) {
            throw new IllegalStateException("Cart is already checked-out");
        }
        this.status = CartStatus.CHECKED_OUT;
        this.checkoutAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isEmpty() {
        return this.cartItems == null || this.cartItems.isEmpty();
    }

    public static Cart createNew(UUID userId){
        Cart cart = new Cart();
        cart.userId = userId;
        cart.status = CartStatus.ACTIVE;
        cart.cartItems = new ArrayList<>();
        return cart;
    }

    public void removeItem(UUID productId){
        cartItems.removeIf(item -> item.getProductId().equals(productId));
    }

    public void clearItems(){
        cartItems.clear();
    }
}