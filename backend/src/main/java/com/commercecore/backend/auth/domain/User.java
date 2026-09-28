package com.commercecore.backend.auth.domain;


import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id",nullable = false,updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** BCrypt / Argon2 hash. Never the raw password. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.CUSTOMER;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {}

    public User( String email, String username, String passwordHash, UserRole role) {
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role == null ? UserRole.CUSTOMER : role;
    }

    // --- getters ---

    public UUID getId()            { return id; }
    public String getEmail()       { return email; }
    public String getUsername()    { return username; }
    public String getPasswordHash(){ return passwordHash; }
    public UserRole getRole()      { return role; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    // --- behavior (domain, not setters) ---

    public void changeEmail(String email)       { this.email = email; }
    public void changeUsername(String username) { this.username = username; }
    public void changePasswordHash(String hash) { this.passwordHash = hash; }
    public void changeRole(UserRole role)       { this.role = role; }

    public void recordFailedLogin() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= 5) {
            this.lockedUntil = Instant.now().plusSeconds(15 * 60); // Lock for 15 mins
        }
    }

    public void recordSuccessfulLogin() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.lastLoginAt = Instant.now();
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return email != null && email.equals(other.email);
    }

    @Override
    public int hashCode() { return email == null ? 0 : email.hashCode(); }
}
