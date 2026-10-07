package com.commercecore.backend.payment.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the `payments` table from V1__schema.sql.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID Id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_intent_id", nullable = false)
    private String providerIntentId;

    /**
     * The webhook event ID from Stripe (e.g., "evt_1Abc123").
     * UNIQUE constraint in DB prevents duplicate processing.
     * Nullable because payment records can be created before
     * the webhook arrives (e.g., during intent creation).
     */
    @Column(name = "provider_event_id", unique = true)
    private String providerEventId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {}

    public Payment(UUID orderId, String provider, String providerIntentId,
                   String providerEventId, BigDecimal amount, String currency,
                   PaymentStatus status, String rawPayload) {
        this.orderId = orderId;
        this.provider = provider;
        this.providerIntentId = providerIntentId;
        this.providerEventId = providerEventId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.rawPayload = rawPayload;
    }

    // --- Domain behavior ---

    public void markCaptured() {
        this.status = PaymentStatus.CAPTURED;
    }

    public void markRefunded() {
        this.status = PaymentStatus.REFUNDED;
    }

    public boolean isCaptured() {
        return this.status == PaymentStatus.CAPTURED;
    }

    // --- Getters ---
    public UUID getId() { return Id; }
    public UUID getOrderId() { return orderId; }
    public String getProvider() { return provider; }
    public String getProviderIntentId() { return providerIntentId; }
    public String getProviderEventId() { return providerEventId; }
    public int getAttemptNo() { return attemptNo; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getRawPayload() { return rawPayload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}