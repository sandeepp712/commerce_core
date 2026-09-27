package com.commercecore.backend.inventory;


import com.commercecore.backend.inventory.repo.InventoryRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class InventoryRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ecommercecore")
            .withUsername("ecommercecore")
            .withPassword("ecommercecore");

    static DataSource dataSource;
    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void beforeAll() {
        HikariConfig cfg=new HikariConfig();
        cfg.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        cfg.setUsername(postgreSQLContainer.getUsername());
        cfg.setPassword(postgreSQLContainer.getPassword());
        cfg.setMaximumPoolSize(20);

        dataSource=new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate=new JdbcTemplate(dataSource);
    }

    @DisplayName("Concurrency Test for 50 buyer to buy 10 product")
    @Test
    void buy_ten_units_exactly_10seconds() throws Exception{

        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO products (id, sku, name, price)
            VALUES (?, ?, ?, ?)
            """, productId, "SKU-CONC-1", "Phone", new BigDecimal("100.00"));

        jdbcTemplate.update("""
            INSERT INTO inventory (product_id, on_hand, reserved)
            VALUES (?, ?, ?)
            """, productId, 10, 0);

    final int THREADS=50;
    final int QTY=1;

    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate  = new CountDownLatch(THREADS);

    AtomicInteger success = new AtomicInteger();
    AtomicInteger soldOut = new AtomicInteger();
    AtomicInteger errors  = new AtomicInteger();
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for(int i=0;i< THREADS ; i++) {
        pool.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);       // each UPDATE is its own transaction

                startGate.await();              // all threads fire at once

                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE inventory
                        SET reserved = reserved + ?
                        WHERE product_id = ?
                          AND on_hand - reserved >= ?
                        """)) {
                    ps.setInt(1, QTY);
                    ps.setObject(2, productId);
                    ps.setInt(3, QTY);

                    int rows = ps.executeUpdate();
                    if (rows == 1) success.incrementAndGet();
                    else           soldOut.incrementAndGet();
                }
            } catch (Throwable t) {
                errors.incrementAndGet();
                failures.add(t);
            } finally {
                doneGate.countDown();
            }
        });
    }
        startGate.countDown();

        assertTrue(doneGate.await(30, TimeUnit.SECONDS),
                "all threads must finish within 30s");
        pool.shutdown();

        // ---------- assertions ----------
        assertEquals(0, errors.get(),
                "no unexpected errors, but got: " + failures);

        assertEquals(10, success.get(),
                "exactly 10 reservations must succeed");

        assertEquals(40, soldOut.get(),
                "exactly 40 must be rejected as SOLD_OUT");

        assertEquals(50, success.get() + soldOut.get(),
                "every thread must be accounted for — no silent drops");

        // final state: 10 reserved, on_hand unchanged (hold, not decrement)
        Integer reserved = jdbcTemplate.queryForObject(
                "SELECT reserved FROM inventory WHERE product_id = ?",
                Integer.class, productId);
        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory WHERE product_id = ?",
                Integer.class, productId);

        assertEquals(10, reserved,
                "reserved must be exactly 10 — the CHECK invariant on_hand - reserved >= 0 held");
        assertEquals(10, onHand,
                "on_hand must remain 10 — this is a reservation, not a permanent decrement");
    }
}