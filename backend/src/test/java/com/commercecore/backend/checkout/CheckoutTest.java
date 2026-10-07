package com.commercecore.backend.checkout;

import com.commercecore.backend.auth.domain.User;
import com.commercecore.backend.auth.domain.UserRole;
import com.commercecore.backend.auth.repo.UserRepository;
import com.commercecore.backend.cart.domain.Cart;
import com.commercecore.backend.cart.domain.CartItem;
import com.commercecore.backend.cart.domain.CartStatus;
import com.commercecore.backend.cart.repo.CartRepository;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.checkout.api.dto.CheckoutRequest;
import com.commercecore.backend.checkout.api.dto.CheckoutResponse;
import com.commercecore.backend.checkout.service.CheckoutService;
import com.commercecore.backend.shared.exception.InsufficientStockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
public class CheckoutTest{
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ecommerce")
                    .withUsername("ecommerce")
                    .withPassword("ecommerce");


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway runs automatically on startup, applying V1-V5 migrations
    }

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private CartRepository cartRepositorySpy;

    UUID userId, productId, cartId;
    private static final int INITIAL_STOCK=5;
    private static final int CHECKOUT_QTY=3;

    @BeforeEach
    void setUp() {
        // ─── 1. Create a User ──────────────────────────────────────────────
        User user = new User("test@example.com", "testuser", "$2a$12$fakehash", UserRole.CUSTOMER);
        user = userRepository.save(user);
        userId = user.getId();

        // ─── 2. Create a Product with price ₹500 ───────────────────────────
        Product product = new Product("SKU-001", "Test Laptop", "A test product",
                new BigDecimal("500.00"), "INR", true);
        product = productRepository.save(product);
        productId = product.getProductId();

        jdbcTemplate.update("""
                INSERT INTO inventory (product_id, on_hand, reserved)
                VALUES (:productId, :onHand, 0)
                ON CONFLICT (product_id) DO UPDATE
                SET on_hand = :onHand, reserved = 0, updated_at = NOW()
                """,
                new MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("onHand", INITIAL_STOCK));
    }

    @AfterEach
    void clean() {
        reset(cartRepositorySpy);
        jdbcTemplate.update("DELETE FROM inventory_reservations", new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM order_items",           new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM orders",                new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM cart_item",             new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM cart",                  new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM inventory",             new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM products",              new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM users",                 new MapSqlParameterSource());
    }


    @Test
    @DisplayName("PHANTOM HOLD DEFEATED: Inventory rolls back when downstream fails after reservation")
    void whenDownstreamFailAfterReservation_inventoryRollsBackCompletely(){
        CartItem cartItem = new CartItem(productId,CHECKOUT_QTY,new BigDecimal("500.00"));
        Cart cart = new Cart(UUID.randomUUID(),userId, CartStatus.ACTIVE, Instant.now(), List.of(cartItem));

        // Mock: findActiveByUserId return valid cart
        when(cartRepositorySpy.findActiveByUserId(userId)).thenReturn(Optional.of(cart));

        doThrow(new RuntimeException("Simulated downstream failure after reservation"))
                .when(cartRepositorySpy).save(any(Cart.class));

        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(),"key-1","coupon");

        assertThatThrownBy(()->
                checkoutService.processCheckout(userId,"idempotency-1",request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated downstream failure");


        // Assertion 1: inventory.reserved is as if checkout never happened
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory WHERE product_id = :productId
                """,
                new MapSqlParameterSource("productId", productId),
                Integer.class);

        assertThat(reserved)
                .as("inventory.reserved must be 0 after rollback. " +
                        "If this is > 0, we have a Phantom Hold: stock is locked " +
                        "with no order to justify it. This is lost revenue.")
                .isEqualTo(0);


        // Assertion 2: inventory.on_hand is unchange(still 5)
        Integer onHand= jdbcTemplate.queryForObject("""
                SELECT on_hand FROM inventory WHERE product_id= :productId
                """,
                new MapSqlParameterSource("productId",productId),Integer.class);

        assertThat(onHand)
                .as("inventory.on_hand must remain unchanged after the rollback")
                .isEqualTo(INITIAL_STOCK);

        // Assertion 3:
        Integer orderCount= jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE idempotency_key= :key 
                """,
                new MapSqlParameterSource("key","idempotency-1"),Integer.class);

        assertThat(orderCount)
                .as("No order should exist after a rolled-back transaction")
                .isEqualTo(0);

        // ASSERTION 4: No inventory_reservations exist
        Integer reservationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inventory_reservations WHERE product_id = :productId
                """,
                new MapSqlParameterSource("productId", productId),
                Integer.class);

        assertThat(reservationCount)
                .as("No reservation rows should exist after rollback")
                .isEqualTo(0);


        // Assertion 5: Cart is still Active (not checkout)
        verify(cartRepositorySpy,times(1)).save(any(Cart.class));
    }


    @Test
    @DisplayName("Inventory is reserved when checkout succeeds")
    void whenCheckoutSucceeds_inventoryIsReserved(){
        // ARRANGE: Same cart, but save() succeeds
        CartItem cartItem = new CartItem(productId, CHECKOUT_QTY, new BigDecimal("500.00"));
        Cart cart = new Cart(UUID.randomUUID(), userId, CartStatus.ACTIVE,
                Instant.now(), List.of(cartItem));

        when(cartRepositorySpy.findActiveByUserId(userId)).thenReturn(Optional.of(cart));
        doReturn(cart).when(cartRepositorySpy).save(any(Cart.class));

        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(),"key-1","coupon");

        // ACT
        CheckoutResponse response = checkoutService.processCheckout(
                userId, "idempotency-key-test-002", request);

        // ASSERT: Inventory IS reserved (the hold is active)
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory WHERE product_id = :productId
                """,
                new MapSqlParameterSource("productId", productId),
                Integer.class);

        assertThat(reserved)
                .as("After successful checkout, reserved must equal the checkout quantity")
                .isEqualTo(CHECKOUT_QTY);

        // ASSERT: on_hand is unchanged (only reserved increases)
        Integer onHand = jdbcTemplate.queryForObject("""
                SELECT on_hand FROM inventory WHERE product_id = :productId
                """,
                new MapSqlParameterSource("productId", productId),
                Integer.class);

        assertThat(onHand).isEqualTo(INITIAL_STOCK);

        // ASSERT: Available = on_hand - reserved = 5 - 3 = 2
        assertThat(onHand - reserved)
                .as("Available stock must be on_hand - reserved")
                .isEqualTo(INITIAL_STOCK - CHECKOUT_QTY);

        // ASSERT: Order was created in AWAITING_PAYMENT_CONFIRMATION
        assertThat(response.state()).isEqualTo("AWAITING_PAYMENT_CONFIRMATION");
    }



    @Test
    @DisplayName("INSUFFICIENT STOCK: Checkout fails atomically, no partial state")
    void whenStockInsufficient_checkoutFailsWithNoPartialState() {

        // ARRANGE: Cart requests 10 units, but only 5 are available
        CartItem cartItem = new CartItem(productId, 10, new BigDecimal("500.00"));
        Cart cart = new Cart(UUID.randomUUID(), userId, CartStatus.ACTIVE,
                Instant.now(), List.of(cartItem));

        when(cartRepositorySpy.findActiveByUserId(userId)).thenReturn(Optional.of(cart));

        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(),"key-1","coupon");

        // ACT & ASSERT
        assertThatThrownBy(() ->
                checkoutService.processCheckout(userId, "idempotency-key-test-003", request))
                .isInstanceOf(InsufficientStockException.class);

        // ASSERT: Nothing changed in the database
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory WHERE product_id = :productId
                """,
                new MapSqlParameterSource("productId", productId),
                Integer.class);

        assertThat(reserved).isEqualTo(0);

        Integer orderCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE idempotency_key = :key
                """,
                new MapSqlParameterSource("key", "idempotency-key-test-003"),
                Integer.class);

        assertThat(orderCount).isEqualTo(0);
    }
}