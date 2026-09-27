package com.commercecore.backend.cart.repo;

import com.commercecore.backend.cart.domain.Cart;
import com.commercecore.backend.cart.domain.CartStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CartRepository extends JpaRepository<Cart, UUID> {

    // Find the active cart belonging to a specific user;
    Optional<Cart> findByCartIdAndUserId(UUID cartId, UUID userId);

    // Find an active cart by ID and status
    Optional<Cart> findByCartIdAndCartStatus(UUID cartId, CartStatus cartStatus);

    Optional<Cart> findActiveByUserId(UUID userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Cart c SET c.cartStatus = :status, c.checkoutAt= :checkoutAt, c.updatedAt = :updatedAt where c.cartId= :cartId")
    int updateCartStatus(
            @Param("cartId") UUID cartId,
            @Param("status") CartStatus status,
            @Param("checkoutAt") Instant checkoutAt,
            @Param("updatedAt") Instant updatedAt
    );
}