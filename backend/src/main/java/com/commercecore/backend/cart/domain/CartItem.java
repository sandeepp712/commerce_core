package com.commercecore.backend.cart.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cart_item")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name ="cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "qty", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt= Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();


    protected CartItem() {}

    public CartItem(UUID productId, int quantity, BigDecimal unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public UUID getId() {return id;}
    public Cart getCart() {return cart;}
    public UUID getProductId() { return this.productId; }
    public int getQuantity() { return this.quantity; }
    public BigDecimal getUnitPrice() { return this.unitPrice; }

    public void setCart(Cart cart) {this.cart = cart;}


    public void incrementQuantity(int amount){
        if(amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        this.quantity += amount;
        this.updatedAt = Instant.now();
    }
}