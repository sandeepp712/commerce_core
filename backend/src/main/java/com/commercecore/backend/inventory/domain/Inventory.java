package com.commercecore.backend.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory")
public class Inventory {
    @Id
    @Column(name = "product_id", updatable = false, nullable = false)
    private UUID productId;

    @Column(name = "on_hand",nullable = false)
    private Integer onHand=0;

    @Column(name = "reserved",nullable = false)
    private Integer reserved=0;

    @Version
    @Column(name = "version",nullable = false)
    private Long version = 0L;

    @Column(name = "updated_at",nullable = false)
    private Instant updatedAt = Instant.now();

    public Inventory() {}

    public Inventory(UUID productId, Integer onHand, Integer reserved) {
        this.productId = productId;
        this.onHand = onHand;
        this.reserved = reserved;
    }

    public UUID getProductId() {return productId;}
    public Integer getOnHand() {return onHand;}
    public Integer getReserved() {return reserved;}


    public void reserveStock(int quantity) {
        if ((onHand - reserved) < quantity) {
            throw new IllegalStateException("Insufficient available stock to reserve");
        }
        this.reserved += quantity;
    }
}