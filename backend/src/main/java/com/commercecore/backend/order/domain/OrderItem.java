package com.commercecore.backend.order.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID orderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id",nullable = false)
    private Order order;

    @Column(name = "product_id",nullable = false)
    private UUID productId;

    @Column(name = "qty",nullable = false)
    private Integer qty;

    @Column(name = "unit_price",nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total",nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "created_at",nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public OrderItem() {}

    public  OrderItem(UUID productId, Integer qty, BigDecimal unitPrice) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Unit price cannot be negative");

        this.productId = productId;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty));
    }


    public UUID getOrderItemId() { return orderItemId; }
    public Order getOrder() { return order; }
    public UUID getProductId() { return productId; }
    public Integer getQty() { return qty; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOrder(Order order) { this.order = order; }
}