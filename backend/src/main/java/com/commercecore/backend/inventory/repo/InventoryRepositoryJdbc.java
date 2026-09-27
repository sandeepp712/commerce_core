package com.commercecore.backend.inventory.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;


@Repository
public class InventoryRepositoryJdbc implements InventoryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    public InventoryRepositoryJdbc(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    // 1. Reserve - the conditional update
    // Prevent overselling, this method atomically checks if there is enough avaiable stock and lock the reserved stock for first requests
    @Override
    public int reserveInventory(UUID productId, int qty) {
        String sql = """
            UPDATE inventory
            SET reserved = reserved + :qty
            WHERE product_id = :productId
              AND (on_hand - reserved) >= :qty
            """;

        var params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("qty", qty);

        return jdbc.update(sql, params);
    }

    // 2. Confirm -> Held -> Confirmed, on_hond-=qty, reserved -=qty
    // Payment successful, change status to confirmed and reduced the stock
    @Override
    public int confirmReservation(UUID reservationId) {
        String sql = """
            WITH updated_reservation AS (
                UPDATE inventory_reservations
                SET status = :targetStatus,
                    confirmed_at=NOW()
                WHERE id = :reservationId
                  AND status = :expectedStatus
                RETURNING product_id, qty
            )
            UPDATE inventory i
            SET on_hand = i.on_hand - ur.qty,
                reserved = i.reserved - ur.qty
            FROM updated_reservation ur
            WHERE i.product_id = ur.product_id
            """;

        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("reservationId", reservationId)
                .addValue("expectedStatus",ReservationStatus.HELD.name())
                .addValue("targetStatus", ReservationStatus.CONFIRMED.name()));
    }


    // 3. Release -> Held -> Released, reserved -=qty
    // Payment failed, the TTL expire the status= Released and decrease the reserved_Stock
    @Override
    public int releaseReservation(UUID reservationId) {
        String sql = """
            WITH updated_reservation AS (
                UPDATE inventory_reservations
                SET status = :targetStatus,
                    released_at = NOW()
                WHERE id = :reservationId
                  AND status = :expectedStatus
                RETURNING product_id, qty
            )
            UPDATE inventory i
            SET reserved = i.reserved - ur.qty,
                updated_at = NOW()
            FROM updated_reservation ur
            WHERE i.product_id = ur.product_id
            """;

        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("reservationId", reservationId)
                .addValue("expectedStatus",ReservationStatus.HELD.name())
                .addValue("targetStatus", ReservationStatus.RELEASED.name()));
    }


    // 4. Insert reservation
    // audit and expiration tracking, stock is reserved this records who holds the reservationa dn when it expires
    @Override
    public UUID insertReservation(UUID orderId, UUID productId, int qty, Instant expiresAt) {
        String INSERT_RESERVATION_SQL= """
            INSERT INTO inventory_reservations
            (id,order_id,product_id,qty,status,expires_at)
            VALUES 
            (:id,:orderId,:productId,:qty,:status,:expiresAt)
            """;

        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_RESERVATION_SQL,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orderId", orderId)
                        .addValue("productId", productId)
                        .addValue("qty", qty)
                        .addValue("status",ReservationStatus.HELD.name())
                        .addValue("expiresAt", expiresAt));
        return id;
    }
}