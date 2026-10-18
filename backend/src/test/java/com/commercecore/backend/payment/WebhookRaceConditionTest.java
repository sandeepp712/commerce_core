package com.commercecore.backend.payment;

import com.commercecore.backend.auth.domain.User;
import com.commercecore.backend.auth.domain.UserRole;
import com.commercecore.backend.auth.repo.UserRepository;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderItem;
import com.commercecore.backend.order.domain.OrderState;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.payment.service.PaymentWebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.util.Assert.state;

@SpringBootTest
@Testcontainers
class WebhookRaceConditionTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ecommerce")
            .withUsername("ecommerce")
            .withPassword("ecommerce");

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("stripe.webhook.signing-secret", () -> "whsec_test_dummy");
    }

    @Autowired private PaymentWebhookService paymentWebhookService;
    @Autowired private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Autowired private OrderRepository  orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;

    private UUID userId;
    private UUID productId;
    private UUID orderId;

    private static final int INITIAL_ON_HAND = 5;
    private static final int CHECKOUT_QUANTITY = 3;
    private static final String EVENT_ID = "evt_happy_path_001";
    private static final String INTENT_ID    = "pi_happy_path_001";
    private static final BigDecimal AMOUNT   = new BigDecimal("1575.00");
    private static final String CURRENCY     = "INR";

    @BeforeEach
    void setUp() {
        userId = userRepository.save(
                new User("happy@test.com", "happyuser", "$2a$12$fakehash", UserRole.CUSTOMER))
                .getId();

        productId = productRepository.save(
                new Product("SKU-HAPPY-001", "Happy Product",
                        "Product for happy path", new BigDecimal("500.00"), "INR",true))
                .getProductId();

        BigDecimal subtotal = new BigDecimal("1500.00");
        BigDecimal shipping = BigDecimal.ZERO;
        BigDecimal tax = new BigDecimal("75.00");

        Order order = Order.builder()
                .userId(userId)
                .state(OrderState.AWAITING_PAYMENT_CONFIRMATION)
                .subtotal(subtotal)
                .shipping(shipping)
                .tax(tax)
                .total(subtotal.add(shipping).add(tax))
                .currency(CURRENCY)
                .shippingAddress("{\"addressId\":\"test-address\"}")
                .idempotencyKey("idem-happy-001")
                .expiresAt(Instant.now().plusSeconds(900))   // not expired
                .build();

        order.addItem(new OrderItem(productId,CHECKOUT_QUANTITY,new BigDecimal("500.00")));
        orderId = orderRepository.save(order).getOrderId();


        // Inventory: 5 on hand, 3 reserved (as they were at checkout).
        namedParameterJdbcTemplate.update("""
                INSERT INTO inventory (product_id, on_hand, reserved)
                VALUES (:productId, :onHand, :reserved)
                """,
                new MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("onHand", INITIAL_ON_HAND)
                        .addValue("reserved", CHECKOUT_QUANTITY));

        // Reservation is HELD — not yet confirmed.
        namedParameterJdbcTemplate.update("""
            INSERT INTO inventory_reservations
                (id, order_id, product_id, qty, status, expires_at, created_at)
            VALUES (:id, :orderId, :productId, :qty, 'HELD', :expiresAt, NOW())
            """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("orderId", orderId)
                        .addValue("productId", productId)
                        .addValue("qty", CHECKOUT_QUANTITY)
                        .addValue("expiresAt",
                                OffsetDateTime.ofInstant(
                                        Instant.now().minusSeconds(60), ZoneOffset.UTC)));

        assertThat(state()).isEqualTo("AWAITING_PAYMENT_CONFIRMATION");
        assertThat(reserved()).isEqualTo(CHECKOUT_QUANTITY);
        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
    }

    @AfterEach
    void clean(){
        namedParameterJdbcTemplate.update("""
                TRUNCATE TABLE
                    pending_refunds, order_events, outbox, inventory_reservations,
                    order_items, payments, orders, shipments, returns, reviews,
                    inventory, products, users, refresh_tokens, addresses,
                    cart_item, cart
                CASCADE
                """, new MapSqlParameterSource());
    }


    @Test
    @DisplayName("Happy Path: webhook on time -> order Paid, reservation CONFIRMED, on_hand decrease")
    void whenWebhookArriveOnTime_orderPaidandInventoryConfirmed(){
        paymentWebhookService.handlePaymentSucceeded(
                EVENT_ID,
                INTENT_ID,
                orderId,
                AMOUNT,
                CURRENCY,
                "{\"type\":\"payment_intent.succeeded\",\"id\":\"" + EVENT_ID + "\"}");

        assertThat(state())
                .as("Order must transition AWAITING_PAYMENT_CONFIRMATION → PAID")
                .isEqualTo("PAID");


        // 1. Order is PAID.
        assertThat(state())
                .as("Order must transition AWAITING_PAYMENT_CONFIRMATION → PAID")
                .isEqualTo("PAID");

        // 2. Reservation is CONFIRMED (the hold converted to a sale).
        assertThat(reservationStatus())
                .as("Reservation must move HELD → CONFIRMED")
                .isEqualTo("CONFIRMED");

        // 3. on_hand decreased by the confirmed quantity.
        assertThat(onHand())
                .as("on_hand must drop from 5 to 2 — the sale is now permanent")
                .isEqualTo(INITIAL_ON_HAND - CHECKOUT_QUANTITY);

        // 4. reserved decreased back to zero (the hold is released as it converts).
        assertThat(reserved())
                .as("reserved must return to 0 after confirmation")
                .isZero();


        // 5. A payment row exists and is CAPTURED, not REFUNDED.
        Integer paymentCount = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE provider_event_id = :e",
                new MapSqlParameterSource("e", EVENT_ID), Integer.class);
        assertThat(paymentCount).isEqualTo(1);

        String paymentStatus = namedParameterJdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE provider_event_id = :e",
                new MapSqlParameterSource("e", EVENT_ID), String.class);
        assertThat(paymentStatus).isEqualTo("CAPTURED");

        // 6. No refund was queued — this is the happy path.
        Integer refundCount = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_refunds WHERE order_id = :o",
                new MapSqlParameterSource("o", orderId), Integer.class);
        assertThat(refundCount)
                .as("No refund on the happy path")
                .isZero();

        // 7. An order event records the transition.
        Integer eventCount = namedParameterJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_events
                WHERE order_id = :o
                  AND event_type = 'PaymentCaptured'
                  AND from_state = 'AWAITING_PAYMENT_CONFIRMATION'
                  AND to_state   = 'PAID'
                """,
                new MapSqlParameterSource("o", orderId), Integer.class);
        assertThat(eventCount)
                .as("A PaymentCaptured event must record the transition")
                .isEqualTo(1);
    }


    @Test
    @DisplayName("IDEMPOTENCY: same webhook delivered twice does not double-confirm inventory")
    void whenSameWebhookDeliveredTwice_inventoryIsNotDoubleConfirmed() {

        paymentWebhookService.handlePaymentSucceeded(
                EVENT_ID, INTENT_ID, orderId, AMOUNT, CURRENCY, "{\"id\":\"" + EVENT_ID + "\"}");

        int onHandAfterFirst = onHand();
        int reservedAfterFirst = reserved();


        // Second delivery — must be a no-op.
        paymentWebhookService.handlePaymentSucceeded(
                EVENT_ID, INTENT_ID, orderId, AMOUNT, CURRENCY, "{\"id\":\"" + EVENT_ID + "\"}");

        assertThat(onHand()).isEqualTo(onHandAfterFirst);
        assertThat(reserved()).isEqualTo(reservedAfterFirst);
        assertThat(reservationStatus()).isEqualTo("CONFIRMED");

        Integer paymentCount = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE provider_event_id = :e",
                new MapSqlParameterSource("e", EVENT_ID), Integer.class);
        assertThat(paymentCount)
                .as("Only one payments row despite two deliveries")
                .isEqualTo(1);
    }



    @Test
    @DisplayName("RACE: webhook arrives after Reaper. Order stays EXPIRED, refund queued, inventory untouched.")
    void whenWebhookArrivesAfterExpiry_orderStaysExpiredAndRefundIsInitiated() {
        // ── Simulate the Reaper winning ─────────────────────────────────
        namedParameterJdbcTemplate.update(
                "UPDATE orders SET state = 'EXPIRED', updated_at = NOW() WHERE id = :o",
                new MapSqlParameterSource("o", orderId));
        namedParameterJdbcTemplate.update(
                "UPDATE inventory SET reserved = 0, updated_at = NOW() WHERE product_id = :p",
                new MapSqlParameterSource("p", productId));
        namedParameterJdbcTemplate.update("""
                UPDATE inventory_reservations SET status = 'RELEASED', released_at = NOW()
                WHERE order_id = :o AND status = 'HELD'
                """, new MapSqlParameterSource("o", orderId));

        // Sanity check: pre-conditions
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(reserved()).isZero();

        // ── ACT: Webhook arrives late ───────────────────────────────────
        paymentWebhookService.handlePaymentSucceeded(
                "evt_race_001", "pi_race_001", orderId, AMOUNT, CURRENCY, "{\"id\":\"evt_race_001\"}");

        // ── ASSERT ──────────────────────────────────────────────────────
        // 1. Order remains EXPIRED (State machine rejected PAID)
        assertThat(state()).isEqualTo("EXPIRED");

        // 2. Inventory is untouched (No double-release)
        assertThat(onHand()).isEqualTo(INITIAL_ON_HAND);
        assertThat(reserved()).isZero();

        // 3. Payment is CAPTURED (Stripe has the money)
        String paymentStatus = namedParameterJdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE provider_event_id = :e",
                new MapSqlParameterSource("e", "evt_race_001"), String.class);
        assertThat(paymentStatus).isEqualTo("CAPTURED");

        // 4. Refund is QUEUED (Our intent to return the money)
        Integer refundCount = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_refunds WHERE order_id = :o",
                new MapSqlParameterSource("o", orderId), Integer.class);
        assertThat(refundCount)
                .as("A PendingRefund must exist because money was captured for a dead order")
                .isEqualTo(1);

        // 5. Order event records the anomaly (NOT silent)
        Integer eventCount = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_events WHERE order_id = :o AND event_type = 'PaymentRefundQueuedAfterExpiry'",
                new MapSqlParameterSource("o", orderId), Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }



    // ── helpers ─────────────────────────────────────────────────────────────

    private String state() {
        return namedParameterJdbcTemplate.queryForObject(
                "SELECT state FROM orders WHERE id = :o",
                new MapSqlParameterSource("o", orderId), String.class);
    }

    private String reservationStatus() {
        return namedParameterJdbcTemplate.queryForObject(
                "SELECT status FROM inventory_reservations WHERE order_id = :o",
                new MapSqlParameterSource("o", orderId), String.class);
    }

    private Integer onHand() {
        return namedParameterJdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE product_id = :p",
                new MapSqlParameterSource("p", productId), Integer.class);
    }

    private Integer reserved() {
        return namedParameterJdbcTemplate.queryForObject(
                "SELECT reserved FROM inventory WHERE product_id = :p",
                new MapSqlParameterSource("p", productId), Integer.class);
    }


}