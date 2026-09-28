package com.commercecore.backend.auth.repo;

import com.commercecore.backend.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // In UserRepository
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.failedLoginAttempts = u.failedLoginAttempts + 1, " +
            "u.lockedUntil = CASE WHEN (u.failedLoginAttempts + 1) >= 5 THEN :lockTime ELSE u.lockedUntil END " +
            "WHERE u.email = :email")
    int incrementFailedAttempts(@Param("email") String email, @Param("lockTime") Instant lockTime);
}