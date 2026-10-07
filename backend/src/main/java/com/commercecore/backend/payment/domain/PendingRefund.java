package com.commercecore.backend.payment.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements Pattern B: Pending Refunds Table + Background Worker.
 *
 * WHY THIS EXISTS:
 * When a webhook arrives for an EXPIRED order, we need to refund.
 * But we CANNOT call Stripe's refund API inside the @Transactional
 * method (connection pool exhaustion risk).
 *
 * Instead, we persist the refund intent INSIDE the transaction,
 * and a background worker executes the actual Stripe API call
 * OUTSIDE any transaction.
 *
 * This survives server crashes: if the server dies after commit
 * but before the refund executes, the worker picks it up on restart.
 */
@Entity
@Table(name = "pending_refunds")
public class PendingRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "provider_intent_id", nullable = false)
    private String providerIntentId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected PendingRefund() {}

    public PendingRefund(UUID orderId, UUID paymentId, String providerIntentId,
                         BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.providerIntentId = providerIntentId;
        this.amount = amount;
        this.currency = currency;
    }

    // --- Domain behavior ---

    public void markCompleted() {
        this.status = RefundStatus.COMPLETED;
        this.processedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
        if (this.retryCount >= 3) {
            this.status = RefundStatus.FAILED;
        }
    }

    public boolean canRetry() {
        return this.status == RefundStatus.PENDING && this.retryCount < 3;
    }

    // --- Getters ---
    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getPaymentId() { return paymentId; }
    public String getProviderIntentId() { return providerIntentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public RefundStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
}