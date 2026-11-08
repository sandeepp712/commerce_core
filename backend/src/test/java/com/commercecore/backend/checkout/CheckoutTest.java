package com.commercecore.backend.checkout;

import com.commercecore.backend.auth.domain.User;
import com.commercecore.backend.auth.domain.UserRole;
import com.commercecore.backend.auth.repo.UserRepository;
import com.commercecore.backend.catalog.domain.Product;
import com.commercecore.backend.catalog.repo.ProductRepository;
import com.commercecore.backend.checkout.api.dto.CheckoutRequest;
import com.commercecore.backend.checkout.api.dto.CheckoutResponse;
import com.commercecore.backend.checkout.service.CheckoutService;
import com.commercecore.backend.order.repo.OrderRepository;
import com.commercecore.backend.shared.exception.InsufficientStockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Testcontainers
public class CheckoutTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ecommerce")
                    .withUsername("ecommerce")
                    .withPassword("ecommerce");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Spy on OrderRepository to simulate downstream failures during the DB write phase
    @MockitoSpyBean
    private OrderRepository orderRepositorySpy;

    UUID userId, productId, cartId;
    private static final int INITIAL_STOCK = 5;
    private static final int CHECKOUT_QTY = 3;
    private static final String STOCK_KEY_PREFIX = "stock:";

    @BeforeEach
    void setUp() {
        // 1. Create a User
        User user = new User("test@example.com", "testuser", "$2a$12$fakehash", UserRole.CUSTOMER);
        user = userRepository.save(user);
        userId = user.getId();

        // 2. Create a Product
        Product product = new Product("SKU-001", "Test Laptop", "A test product",
                new BigDecimal("500.00"), "INR", true);
        product = productRepository.save(product);
        productId = product.getProductId();

        // 3. Seed Database Inventory
        jdbcTemplate.update("""
                INSERT INTO inventory (product_id, on_hand, reserved)
                VALUES (:productId, :onHand, 0)
                ON CONFLICT (product_id) DO UPDATE
                SET on_hand = :onHand, reserved = 0, updated_at = NOW()
                """,
                new MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("onHand", INITIAL_STOCK));

        // 4. Create and Save Cart in DB (Crucial for Foreign Key constraint)
        cartId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO cart (id, user_id, status, created_at, updated_at)
                VALUES (:id, :userId, 'ACTIVE', NOW(), NOW())
                """,
                new MapSqlParameterSource()
                        .addValue("id", cartId)
                        .addValue("userId", userId));

        // 5. Create and Save CartItem in DB
        jdbcTemplate.update("""
                INSERT INTO cart_item (id, cart_id, product_id, qty, unit_price, created_at, updated_at)
                VALUES (:id, :cartId, :productId, :qty, :price, NOW(), NOW())
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("cartId", cartId)
                        .addValue("productId", productId)
                        .addValue("qty", CHECKOUT_QTY)
                        .addValue("price", new BigDecimal("500.00")));

        // 6. Seed Redis Inventory
        String redisKey = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(INITIAL_STOCK));
    }

    @AfterEach
    void clean() {
        MapSqlParameterSource emptyParams = new MapSqlParameterSource();
        jdbcTemplate.update("DELETE FROM inventory_reservations", emptyParams);
        jdbcTemplate.update("DELETE FROM order_items", emptyParams);
        jdbcTemplate.update("DELETE FROM orders", emptyParams);
        jdbcTemplate.update("DELETE FROM cart_item", emptyParams);
        jdbcTemplate.update("DELETE FROM cart", emptyParams);
        jdbcTemplate.update("DELETE FROM inventory", emptyParams);
        jdbcTemplate.update("DELETE FROM products", emptyParams);
        jdbcTemplate.update("DELETE FROM users", emptyParams);

        String redisKey = STOCK_KEY_PREFIX + productId;
        redisTemplate.delete(redisKey);
    }

    @Test
    @DisplayName("PHANTOM HOLD DEFEATED: Inventory rolls back in DB and Redis when downstream fails")
    void whenDownstreamFailAfterReservation_inventoryRollsBackCompletely() {
        // Simulate a downstream failure during the DB write phase
        doThrow(new RuntimeException("Simulated downstream failure after reservation"))
                .when(orderRepositorySpy).saveAndFlush(any());

        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(), "idempotency-1", "coupon");

        assertThatThrownBy(() ->
                checkoutService.processCheckout(userId, "idempotency-1", request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated downstream failure");

        String redisKey = STOCK_KEY_PREFIX + productId;

        // Assertion 1: DB inventory.reserved is 0
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory WHERE product_id = :productId
                """, new MapSqlParameterSource("productId", productId), Integer.class);
        assertThat(reserved).isEqualTo(0);

        // Assertion 2: DB inventory.on_hand is unchanged
        Integer onHand = jdbcTemplate.queryForObject("""
                SELECT on_hand FROM inventory WHERE product_id = :productId
                """, new MapSqlParameterSource("productId", productId), Integer.class);
        assertThat(onHand).isEqualTo(INITIAL_STOCK);

        // Assertion 3: No order exists
        Integer orderCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE idempotency_key = :key 
                """, new MapSqlParameterSource("key", "idempotency-1"), Integer.class);
        assertThat(orderCount).isEqualTo(0);

        // Assertion 4: No inventory_reservations exist
        Integer reservationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inventory_reservations WHERE product_id = :productId
                """, new MapSqlParameterSource("productId", productId), Integer.class);
        assertThat(reservationCount).isEqualTo(0);

        // Assertion 5: REDIS stock is rolled back to INITIAL_STOCK
        String redisStock = redisTemplate.opsForValue().get(redisKey);
        assertThat(redisStock)
                .as("Redis stock must be rolled back to initial state after failure")
                .isEqualTo(String.valueOf(INITIAL_STOCK));
    }

    @Test
    @DisplayName("Inventory is reserved in DB and Redis when checkout succeeds")
    void whenCheckoutSucceeds_inventoryIsReserved() {
        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(), "idempotency-key-test-002", "coupon");

        CheckoutResponse response = checkoutService.processCheckout(userId, "idempotency-key-test-002", request);

        String redisKey = STOCK_KEY_PREFIX + productId;

        // ASSERT: DB Inventory IS reserved
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory WHERE product_id = :productId
                """, new MapSqlParameterSource("productId", productId), Integer.class);
        assertThat(reserved).isEqualTo(CHECKOUT_QTY);

        Integer onHand = jdbcTemplate.queryForObject("""
                SELECT on_hand FROM inventory WHERE product_id = :productId
                """, new MapSqlParameterSource("productId", productId), Integer.class);
        assertThat(onHand).isEqualTo(INITIAL_STOCK);

        // ASSERT: REDIS stock is decremented
        String redisStock = redisTemplate.opsForValue().get(redisKey);
        assertThat(redisStock)
                .as("Redis stock must be decremented by checkout quantity")
                .isEqualTo(String.valueOf(INITIAL_STOCK - CHECKOUT_QTY));

        // ASSERT: Order was created
        assertThat(response.state()).isEqualTo("AWAITING_PAYMENT_CONFIRMATION");
    }

    @Test
    @DisplayName("INSUFFICIENT STOCK: Checkout fails atomically, Redis and DB remain unchanged")
    void whenStockInsufficient_checkoutFailsWithNoPartialState() {
        // ARRANGE: Update cart to request 10 units, but only 5 are available
        jdbcTemplate.update("""
                UPDATE cart_item SET qty = 10 WHERE cart_id = :cartId
                """, new MapSqlParameterSource("cartId", cartId));

        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(), "idempotency-key-test-003", "coupon");

        // ACT & ASSERT
        assertThatThrownBy(() ->
                checkoutService.processCheckout(userId, "idempotency-key-test-003", request))
                .isInstanceOf(InsufficientStockException.class);

        String redisKey = STOCK_KEY_PREFIX + productId;

        // ASSERT: DB Nothing changed
        Integer reserved = jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory WHERE product_id = :productId
                """, new MapSqlParameterSource("productId", productId), Integer.class);
        assertThat(reserved).isEqualTo(0);

        Integer orderCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE idempotency_key = :key
                """, new MapSqlParameterSource("key", "idempotency-key-test-003"), Integer.class);
        assertThat(orderCount).isEqualTo(0);

        // ASSERT: REDIS stock is unchanged (compensation worked)
        String redisStock = redisTemplate.opsForValue().get(redisKey);
        assertThat(redisStock)
                .as("Redis stock must remain unchanged when checkout fails due to insufficient stock")
                .isEqualTo(String.valueOf(INITIAL_STOCK));
    }
}