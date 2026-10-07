package com.commercecore.backend.payment.controller;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies Stripe webhook signatures using HMAC-SHA256.
 *
 * HOW STRIPE SIGNS WEBHOOKS:
 * 1. Stripe takes: timestamp + "." + raw_json_body
 * 2. Computes: HMAC-SHA256(that_string, webhook_signing_secret)
 * 3. Sends header: Stripe-Signature: t=<timestamp>,v1=<hex_signature>
 *
 * HOW WE VERIFY:
 * 1. Extract timestamp and v1 from the header
 * 2. Recompute HMAC-SHA256(timestamp + "." + rawBody, our_secret)
 * 3. Compare using constant-time comparison (prevents timing attacks)
 *
 * WHY CONSTANT-TIME COMPARISON:
 * If we use String.equals(), an attacker can measure response times
 * to guess the signature byte-by-byte. MessageDigest.isEqual()
 * always takes the same time regardless of where the mismatch occurs.
 */
public final class WebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private WebhookSignatureVerifier() {}

    /**
     * Verifies a Stripe webhook signature.
     *
     * @param rawBody        The EXACT raw request body as received (not parsed/re-serialized)
     * @param signatureHeader The value of the Stripe-Signature header
     * @param signingSecret  The webhook signing secret (whsec_...)
     * @return true if the signature is valid, false otherwise
     */
    public static boolean isValid(String rawBody, String signatureHeader, String signingSecret) {
        if (rawBody == null || signatureHeader == null || signingSecret == null) {
            return false;
        }

        try {
            // Parse the header: "t=1614556828,v1=5257a869e7e...,v0=..."
            String timestamp = null;
            String expectedSignature = null;

            String[] parts = signatureHeader.split(",");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0].trim())) {
                    timestamp = kv[1].trim();
                } else if ("v1".equals(kv[0].trim())) {
                    expectedSignature = kv[1].trim();
                }
            }

            if (timestamp == null || expectedSignature == null) {
                return false;
            }

            // Compute: HMAC-SHA256(timestamp + "." + rawBody, secret)
            String payload = timestamp + "." + rawBody;
            String computedSignature = computeHmacSha256(payload, signingSecret);

            // Constant-time comparison to prevent timing attacks
            byte[] computedBytes = computedSignature.getBytes(StandardCharsets.UTF_8);
            byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.UTF_8);

            return MessageDigest.isEqual(computedBytes, expectedBytes);

        } catch (Exception e) {
            // Any parsing/crypto error means invalid signature
            return false;
        }
    }

    private static String computeHmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(keySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}