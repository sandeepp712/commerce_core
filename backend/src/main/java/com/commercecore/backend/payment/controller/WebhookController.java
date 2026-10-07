package com.commercecore.backend.payment.controller;

import com.commercecore.backend.payment.service.PaymentWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stripe Webhook Controller.
 *
 * CRITICAL IMPLEMENTATION DETAIL:
 * The method parameter is `String rawBody`, NOT a DTO.
 * We MUST read the raw bytes for HMAC verification.
 * If Spring auto-parses the JSON into a DTO before we verify,
 * the byte representation might differ (whitespace, field ordering),
 * and the HMAC check will fail.
 *
 * FLOW:
 * 1. Read raw body as String
 * 2. Verify HMAC signature
 * 3. Parse JSON manually (only after verification)
 * 4. Dispatch to service based on event type
 * 5. Return 200 OK (even for duplicates — Stripe expects 2xx to stop retrying)
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentWebhookService paymentWebhookService;
    private final ObjectMapper objectMapper;

    @Value("${STRIPE_WEBHOOK_SECRET:whsec_test_dummy}")
    private String webhookSigningSecret;

    public WebhookController(PaymentWebhookService paymentWebhookService,
                             ObjectMapper objectMapper) {
        this.paymentWebhookService = paymentWebhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader,
            @RequestBody String rawBody) {  // RAW STRING, not a DTO!

        // ─── STEP 1: VERIFY SIGNATURE ──────────────────────────────────────
        if (signatureHeader == null || signatureHeader.isBlank()) {
            LOG.warn("Webhook rejected: missing Stripe-Signature header");
            return ResponseEntity.status(401).body("Missing signature");
        }

        boolean valid = WebhookSignatureVerifier.isValid(rawBody, signatureHeader, webhookSigningSecret);
        if (!valid) {
            LOG.warn("Webhook rejected: invalid signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // ─── STEP 2: PARSE EVENT (only after verification) ─────────────────
        try {
            JsonNode event = objectMapper.readTree(rawBody);
            String eventType = event.path("type").asText();
            String eventId = event.path("id").asText();

            LOG.info("Received webhook: type={}, id={}", eventType, eventId);

            // ─── STEP 3: DISPATCH BY EVENT TYPE ────────────────────────────
            switch (eventType) {
                case "payment_intent.succeeded" -> handlePaymentSucceeded(event, eventId);
                case "payment_intent.payment_failed" -> handlePaymentFailed(event, eventId);
                default -> LOG.debug("Ignoring unhandled event type: {}", eventType);
            }

        } catch (Exception e) {
            // Log the error but still return 200.
            // WHY: If we return 500, Stripe will retry the webhook.
            // If the error is a bug in our code (not a transient failure),
            // Stripe will retry infinitely, creating noise.
            // Better: return 200, log the error, investigate manually.
            LOG.error("Error processing webhook: {}", e.getMessage(), e);
        }

        // Always return 200 for verified webhooks.
        // Stripe interprets 2xx as "successfully received, stop retrying."
        return ResponseEntity.ok("OK");
    }

    private void handlePaymentSucceeded(JsonNode event, String eventId) throws Exception {
        JsonNode paymentIntent = event.path("data").path("object");

        String paymentIntentId = paymentIntent.path("id").asText();
        String orderIdStr = paymentIntent.path("metadata").path("order_id").asText();
        long amountInCents = paymentIntent.path("amount").asLong();
        String currency = paymentIntent.path("currency").asText().toUpperCase();

        if (orderIdStr == null || orderIdStr.isBlank()) {
            LOG.error("Webhook {} missing order_id in metadata. Cannot process.", eventId);
            return;
        }

        UUID orderId = UUID.fromString(orderIdStr);
        // Stripe amounts are in cents (e.g., 5000 = $50.00)
        BigDecimal amount = BigDecimal.valueOf(amountInCents).movePointLeft(2);

        paymentWebhookService.handlePaymentSucceeded(
                eventId,
                paymentIntentId,
                orderId,
                amount,
                currency,
                event.toString()  // Store the full event JSON as raw_payload
        );
    }

    private void handlePaymentFailed(JsonNode event, String eventId) {
        // TODO: Implement payment failure handling
        // Transition order to CANCELLED, release inventory
        LOG.info("Payment failed for event: {}", eventId);
    }
}