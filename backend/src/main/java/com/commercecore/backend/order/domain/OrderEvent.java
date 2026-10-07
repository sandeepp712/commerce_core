package com.commercecore.backend.order.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable record that something happened to an order at a point in time.
 *
 * STATE vs EVENT:
 *   - orders.state   = a condition that persists ("EXPIRED" for two hours)
 *   - order_events   = moments that flipped it ("PaymentCapturedAfterExpiry" at 14:01:10)
 *
 * This entity is APPEND-ONLY. There are no setters, no update methods, no delete.
 * An event that happened cannot un-happen. If the world changes, write a new event.
 */
@Entity
@Table(name = "order_events")
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "from_state", updatable = false, length = 40)
    private String fromState;

    @Column(name = "to_state", updatable = false, length = 40)
    private String toState;

    /** Raw payload as JSONB. Audit trail: what the provider sent, what the service decided. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderEvent() {}

    public OrderEvent(UUID orderId, String eventType,
                      String fromState, String toState,
                      String payload) {
        this.orderId    = orderId;
        this.eventType  = eventType;
        this.fromState  = fromState;
        this.toState    = toState;
        this.payload    = payload;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    // --- Factory methods: the only way to create an event ---

    /** A state transition. from_state and to_state must both be set. */
    public static OrderEvent transition(UUID orderId,
                                        String eventType,
                                        String fromState,
                                        String toState,
                                        String payload) {
        if (fromState == null || toState == null) {
            throw new IllegalArgumentException(
                    "A transition event must record both from_state and to_state");
        }
        return new OrderEvent(orderId, eventType, fromState, toState, payload);
    }

    /** A fact that did not move the state machine. from_state == to_state. */
    public static OrderEvent fact(UUID orderId,
                                  String eventType,
                                  String currentState,
                                  String payload) {
        return new OrderEvent(orderId, eventType, currentState, currentState, payload);
    }

    /** A pure audit event with no state context. */
    public static OrderEvent audit(UUID orderId, String eventType, String payload) {
        return new OrderEvent(orderId, eventType, null, null, payload);
    }

    // --- Getters only. No setters. Immutable. ---

    public UUID getId()           { return id; }
    public UUID getOrderId()      { return orderId; }
    public String getEventType()  { return eventType; }
    public String getFromState()  { return fromState; }
    public String getToState()    { return toState; }
    public String getPayload()    { return payload; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderEvent other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return id == null ? 0 : id.hashCode(); }
}