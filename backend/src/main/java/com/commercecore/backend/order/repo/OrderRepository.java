package com.commercecore.backend.order.repo;

import com.commercecore.backend.order.domain.Order;
import com.commercecore.backend.order.domain.OrderState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

     Optional<Order> findByIdempotencyKey(String idempotencyKey);

     List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);
}