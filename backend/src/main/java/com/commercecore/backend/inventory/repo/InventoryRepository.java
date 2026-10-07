package com.commercecore.backend.inventory.repo;

import java.util.UUID;

public interface InventoryRepository{

    /** @return rowcount:  reserved=1 , sold out=0; */
    int reserveInventory(UUID productId, int qty);

    /** @return rowcount: confirmed=1, not held=1(already confirmed/released) */
//    int confirmReservation(UUID reservationId);

    /** @return rowcount: released=1, not held=1 (already confirmed/released) */
    int releaseReservation(UUID reservationId);

    /** Insert the reservation row. Called inside the same transaction as reserveInventory */
    UUID insertReservation(UUID orderId,UUID productId, int qty, java.time.Instant expiresAt);

    // Add to InventoryRepository interface:
    int confirmAllReservationsForOrder(UUID orderId);
}