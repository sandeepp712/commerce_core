package com.commercecore.backend.payment.repo;

import com.commercecore.backend.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByProviderEventId(String providerEventId);

    Optional<Payment> findByProviderAndProviderIntentId(String provider, String providerIntentId);

    Optional<Payment> findByOrderId(UUID orderId);
}