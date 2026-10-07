package com.commercecore.backend.auth.repo;

import com.commercecore.backend.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
        UPDATE RefreshToken t
        SET t.revokedAt = :now
        WHERE t.familyId = :familyId AND t.revokedAt IS NULL
        """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);


    @Modifying
    @Query("""
        UPDATE RefreshToken t
        SET t.revokedAt = :now
        WHERE t.userId = :userId AND t.revokedAt IS NULL
        """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    // Periodic Cleanup job
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}