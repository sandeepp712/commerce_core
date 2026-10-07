package com.commercecore.backend.shared.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private static final long ACCESS_TOKEN_EXPIRY_MS = 15 * 60 * 1000;  // 15 minutes

    public JwtService(@Value("${JWT_SECRET:fallback_secret_key_for_development}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UUID userId, String role) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusMillis(ACCESS_TOKEN_EXPIRY_MS)))
                .signWith(key)
                .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaims(token).getSubject());
    }

    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;  // Expired, malformed, or bad signature
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)  // Verifies HMAC signature
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}