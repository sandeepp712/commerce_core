package com.commercecore.backend.payment.repo;

import com.commercecore.backend.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByProviderEventId(String providerEventId);

    Optional<Payment> findByProviderEventId(String providerEventId);

    Optional<Payment> findByProviderAndProviderIntentId(String provider, String providerIntentId);

    Optional<Payment> findByOrderId(UUID orderId);

    @Modifying
    @Query(value = """
        INSERT INTO payments (
            id, order_id, provider, provider_intent_id, provider_event_id,
            amount, currency, status, raw_payload, attempt_no, created_at, updated_at
        ) VALUES (
            :id, :orderId, :provider, :intentId, :eventId,
            :amount, :currency, :status, CAST(:payload as jsonb), 1, NOW(), NOW()
        )
        ON CONFLICT (provider_event_id) DO NOTHING
        """, nativeQuery = true)
    int insertIgnoreDuplicate(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("provider") String provider,
            @Param("intentId") String intentId,
            @Param("eventId") String eventId,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("status") String status,
            @Param("payload") String payload);
}