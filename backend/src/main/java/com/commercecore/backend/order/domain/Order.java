package com.commercecore.backend.order.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID orderId;

    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private OrderState state;

    @Column(name = "subtotal", nullable = false)
    private BigDecimal subtotal;

    @Column(name = "shipping", nullable = false)
    private BigDecimal shipping;

    @Column(name = "tax", nullable = false)
    private BigDecimal tax;

    @Column(name = "total", nullable = false)
    private BigDecimal total;

    @Column(name = "currency",nullable = false,columnDefinition = "char(3)")
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }


    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    protected Order() {}

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.userId = builder.userId;
        this.state = builder.state;
        this.subtotal = builder.subtotal;
        this.shipping = builder.shipping;
        this.tax = builder.tax;
        this.total = builder.total;
        this.currency = builder.currency;
        this.shippingAddress = builder.shippingAddress;
        this.idempotencyKey = builder.idempotencyKey;
        this.expiresAt = builder.expiresAt;

        if(builder.orderItems != null){
            builder.orderItems.forEach(this::addItem);
        }
    }


    public UUID getOrderId() {return orderId;}
    public UUID getUserId() {return userId;}
    public OrderState getState() {return state;}
    public BigDecimal getSubtotal() {return subtotal;}
    public BigDecimal getShipping() {return shipping;}
    public BigDecimal getTax() {return tax;}
    public BigDecimal getTotal() {return total;}
    public String getCurrency() {return currency;}
    public String getShippingAddress() {return shippingAddress;}
    public String getIdempotencyKey() {return idempotencyKey;}
    public Instant getCreatedAt() {return createdAt;}
    public Instant getUpdatedAt() {return updatedAt;}
    public Instant getExpiresAt() {return expiresAt;}
    public List<OrderItem> getOrderItems() {return Collections.unmodifiableList(orderItems);}


    /**
     * Domain guard. Throws unless the edge is legal.
     */
    public void transitionTo(OrderState next) {
        OrderStateMachine.assertTransition(this.state, next);
        this.state = next;
    }

    public void addItem(OrderItem item) {
        if (item == null) throw new IllegalArgumentException("OrderItem cannot be null");
        item.setOrder(this);
        this.orderItems.add(item);
    }


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID orderId;
        private UUID userId;
        private OrderState state;
        private BigDecimal subtotal;
        private BigDecimal shipping;
        private BigDecimal tax;
        private BigDecimal total;
        private String currency = "INR";
        private String shippingAddress;
        private String idempotencyKey;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant expiresAt;
        private List<OrderItem> orderItems = new ArrayList<>();

        public Builder orderId(UUID orderId) {this.orderId = orderId;return this;}
        public Builder userId(UUID userId) {this.userId = userId;return this;}
        public Builder state(OrderState state) {this.state = state;return this;}
        public Builder subtotal(BigDecimal subtotal) {this.subtotal = subtotal;return this;}
        public Builder shipping(BigDecimal shipping) {this.shipping = shipping;return this;}
        public  Builder tax(BigDecimal tax) {this.tax = tax;return this;}
        public Builder total(BigDecimal total) {this.total = total;return this;}
        public Builder currency(String currency) {this.currency = currency;return this;}
        public Builder  shippingAddress(String shippingAddress) {this.shippingAddress = shippingAddress;return this;}
        public Builder idempotencyKey(String idempotencyKey) {this.idempotencyKey = idempotencyKey;return this;}
        public Builder expiresAt(Instant expiresAt) {this.expiresAt = expiresAt;return this;}

        public Builder addItems(List<OrderItem> items) {this.orderItems.addAll(items);return this;}

        public Builder orderItems(List<OrderItem> orderItems) {
            if(orderItems != null) this.orderItems.addAll(orderItems);
            return this;
        }
        public Order build() {
            if(orderId == null) throw new IllegalStateException("OrderId cannot be null");
            return new Order(this);
        }
    }

}