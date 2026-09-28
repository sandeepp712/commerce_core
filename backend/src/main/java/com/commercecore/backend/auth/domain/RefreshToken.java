package com.commercecore.backend.auth.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id",nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public RefreshToken(UUID userId, String tokenHash, UUID familyId,
                        String deviceInfo, String ipAddress, Instant expiresAt) {
        this.userId     = userId;
        this.tokenHash  = tokenHash;
        this.familyId   = familyId;
        this.deviceInfo = deviceInfo;
        this.ipAddress  = ipAddress;
        this.expiresAt  = expiresAt;
    }

    public boolean isActive(Instant now){
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now){
        this.revokedAt = now;
    }

    public void markReplacedBy(UUID uuid){
        this.replacedBy = uuid;
    }

    public UUID getId() {return id;}
    public UUID getUserId() {return userId;}
    public String getTokenHash() {return tokenHash;}
    public UUID getFamilyId() {return familyId;}
    public String getDeviceInfo() {return deviceInfo;}
    public String getIpAddress() {return ipAddress;}
    public Instant getExpiresAt() {return expiresAt;}
    public Instant getRevokedAt() {return revokedAt;}
    public UUID getReplacedBy() {return replacedBy;}
    public Instant getCreatedAt() {return createdAt;}
}