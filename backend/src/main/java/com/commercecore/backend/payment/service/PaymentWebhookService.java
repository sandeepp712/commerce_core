package com.commercecore.backend.payment.service;

import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderEvent;
import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.repo.OrderEventRepository;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.payment.domain.Payment;
import com.commercecore.backend.payment.domain.PaymentStatus;
import com.commercecore.backend.payment.domain.PendingRefund;
import com.commercecore.backend.payment.repo.PaymentRepository;
import com.commercecore.backend.payment.repo.PendingRefundRepository;
import com.commercecore.backend.shared.exception.IllegalOrderTransitionException;
import com.commercecore.backend.shared.outbox.OutboxEvent;
import com.commercecore.backend.shared.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Handles the business logic for payment webhooks.
 *
 * CRITICAL DESIGN PRINCIPLES:
 * 1. Idempotency: provider_event_id UNIQUE constraint prevents duplicates.
 * 2. Atomicity: All state changes happen in ONE transaction.
 * 3. No external calls inside transactions: Refunds go to pending_refunds table.
 * 4. State machine is the arbiter: IllegalOrderTransitionException means
 *    the order is in a terminal/unexpected state.
 */
@Service
public class PaymentWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PendingRefundRepository pendingRefundRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderEventRepository orderEventRepository;
    private final OutboxRepository outboxRepository;

    public PaymentWebhookService(OrderRepository orderRepository,
                                 PaymentRepository paymentRepository,
                                 PendingRefundRepository pendingRefundRepository,
                                 InventoryRepository inventoryRepository,
                                 OrderEventRepository orderEventRepository,
                                 OutboxRepository outboxRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.pendingRefundRepository = pendingRefundRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderEventRepository=orderEventRepository;
        this.outboxRepository=outboxRepository;
    }

    /**
     * Handles a "payment_intent.succeeded" webhook event.
     *
     * EXECUTION FLOW:
     * 1. Deduplicate via provider_event_id UNIQUE constraint
     * 2. Load order and attempt state transition
     * 3. If order is AWAITING_PAYMENT → transition to PAID, confirm inventory
     * 4. If order is EXPIRED → persist a PendingRefund (do NOT call Stripe here)
     * 5. Write order event + outbox event (inside same transaction)
     *
     * @param eventId          Stripe event ID (e.g., "evt_1Abc123")
     * @param paymentIntentId  Stripe payment intent ID (e.g., "pi_1Abc123")
     * @param orderId          The order this payment is for
     * @param amount           The captured amount
     * @param currency         The currency code
     * @param rawPayload       The full webhook JSON (for audit trail)
     */
    @Transactional(rollbackFor = Exception.class)
    public void handlePaymentSucceeded(String eventId, String paymentIntentId,
                                       UUID orderId, BigDecimal amount,
                                       String currency, String rawPayload) {

        // ─── STEP 1: IDEMPOTENCY CHECK ─────────────────────────────────────
        // Attempt to insert a payment record with the event ID.
        // If this event was already processed, the UNIQUE constraint
        // on provider_event_id will throw DataIntegrityViolationException.
        Payment payment = new Payment(
                orderId,
                "STRIPE",
                paymentIntentId,
                eventId,
                amount,
                currency,
                PaymentStatus.CAPTURED,
                rawPayload
        );

        try {
            paymentRepository.save(payment);
        } catch (DataIntegrityViolationException e) {
            // This event was already processed. Idempotent success.
            LOG.info("Duplicate webhook event ignored: {}", eventId);
            return;
        }

        // ─── STEP 2: LOAD ORDER ────────────────────────────────────────────
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Order not found for payment webhook: " + orderId));

        // ─── STEP 3: STATE TRANSITION ──────────────────────────────────────
        try {
            // The state machine enforces: AWAITING_PAYMENT_CONFIRMATION → PAID
            // If the order is EXPIRED, CANCELLED, or already PAID,
            // this will throw IllegalOrderTransitionException.
            order.transitionTo(OrderState.PAID);

        } catch (IllegalOrderTransitionException e) {
            // ─── RACE CONDITION HANDLER ────────────────────────────────────
            // The order is NOT in AWAITING_PAYMENT_CONFIRMATION.
            // Most likely: the TTL Reaper already expired it.
            // The money was captured, but the order is dead.
            // We must refund. But NOT here (no external calls in transaction).
            // Instead: persist the refund intent for the background worker.

            LOG.warn("Payment succeeded but order is in state {}. " +
                            "Order {}. Initiating refund.",
                    order.getState(), orderId);

            PendingRefund refund = new PendingRefund(
                    orderId,
                    payment.getId(),
                    paymentIntentId,
                    amount,
                    currency
            );
            pendingRefundRepository.save(refund);

            // Mark payment as refunded (intent) since we know we'll refund
//            payment.markRefunded();
            orderEventRepository.save(new OrderEvent(
                    orderId,
                    "PaymentRefundQueuedAfterExpiry",
                    order.getState().name(),
                    null,
                    String.format("{\"eventId\":\"%s\",\"reason\":\"order_expired_before_payment_confirmed\"}", eventId)
            ));

            // ─── STEP 7: WRITE OUTBOX (same transaction) ───────────────────────
            outboxRepository.save(new OutboxEvent(
                    "Order", orderId, "PaymentRefundQueued",
                    String.format("{\"orderId\":\"%s\",\"amount\":%s,\"currency\":\"%s\",\"reason\":\"expired\"}",
                            orderId, amount, currency)
            ));



            return;
        }

        // ─── STEP 4: CONFIRM INVENTORY RESERVATIONS ────────────────────────
        // The order successfully transitioned to PAID.
        // Now confirm all HELD reservations → CONFIRMED.
        // This uses the CTE in InventoryRepositoryJdbc:
        //   UPDATE reservations SET status='CONFIRMED' WHERE order_id AND status='HELD'
        //   UPDATE inventory SET on_hand -= qty, reserved -= qty
        inventoryRepository.confirmAllReservationsForOrder(orderId);

        // ─── STEP 5: PERSIST THE ORDER STATE CHANGE ────────────────────────
        orderRepository.save(order);

        // ─── STEP 6: WRITE ORDER EVENT ─────────────────────────────────────
        // (Assuming you have an OrderEventRepository)
        // orderEventRepository.save(new OrderEvent(
        //     orderId, "PAYMENT_CAPTURED", "AWAITING_PAYMENT_CONFIRMATION", "PAID", rawPayload
        // ));
        orderEventRepository.save(new OrderEvent(
                orderId,
                "PaymentCaptured",
                "AWAITING_PAYMENT_CONFIRMATION",
                "PAID",
                String.format("{\"eventId\":\"%s\",\"amount\":%s}", eventId, amount)
        ));

        // ─── STEP 7: WRITE OUTBOX EVENT ────────────────────────────────────
        // (Assuming you have an OutboxRepository)
        // outboxRepository.save(new OutboxEvent(
        //     "Order", orderId, "OrderPaid",
        //     "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}"
        // ));
        // ─── STEP 7: WRITE OUTBOX (same transaction as state change) ───────────
        outboxRepository.save(new OutboxEvent(
                "Order", orderId, "OrderPaid",
                String.format("{\"orderId\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}",
                        orderId, amount, currency)
        ));


        LOG.info("Order {} transitioned to PAID. Inventory confirmed. " +
                "Outbox event written.", orderId);
    }
}