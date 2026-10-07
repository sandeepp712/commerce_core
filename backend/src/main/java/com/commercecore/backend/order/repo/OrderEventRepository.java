package com.commercecore.backend.order.repo;

import com.commercecore.backend.order.domain.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {

}