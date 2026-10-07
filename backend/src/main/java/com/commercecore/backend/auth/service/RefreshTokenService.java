package com.commercecore.backend.auth.service;

import com.commercecore.backend.auth.repo.RefreshTokenRepository;
import com.commercecore.backend.auth.domain.RefreshToken;
import com.commercecore.backend.shared.exception.InvalidRefreshTokenException;
import com.commercecore.backend.shared.exception.RefreshTokenReusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final Duration REFRESH_TOKEN_EXPIRATION_PERIOD = Duration.ofDays(30);
    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // Issue for a brand new token login
    @Transactional
    public Issued issueNew(UUID userId,String deviceInfo, String ip){
        String rawToken = RefreshTokens.newRawToken();
        String hash= RefreshTokens.hash(rawToken);
        UUID family = UUID.randomUUID();
        Instant exp = Instant.now().plus(REFRESH_TOKEN_EXPIRATION_PERIOD);

        RefreshToken row=new RefreshToken(userId,hash,family,deviceInfo,ip,exp);

        refreshTokenRepository.save(row);

        return new Issued(rawToken,exp);
    }

    // Rotate validate, revoke the old, issue a new one in the same family
    // Reuse detection: if a revoked token is presented, revoke the whole family
    @Transactional
    public Rotation rotate(String rawToken,String ip){
        String hash = RefreshTokens.hash(rawToken);
        RefreshToken current = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(()-> { throw new InvalidRefreshTokenException("Invalid refresh token");
                });

        Instant now = Instant.now();

        // Reuse detected: the token is revoked but still being presented
        if (current.getRevokedAt() != null) {
            LOGGER.warn("Refresh token reuse detected for user{}, famiky{}",
                    current.getUserId(),current.getFamilyId());
            refreshTokenRepository.revokeFamily(current.getFamilyId(),now);
            throw new RefreshTokenReusedException("Refresh token reuse detected for user"+current.getUserId());
        }

        if(!current.isActive(now)){
            throw new RefreshTokenReusedException("Refresh token reuse detected for user"+current.getUserId());
        }

        String newRawToken = RefreshTokens.newRawToken();
        String newHash = RefreshTokens.hash(newRawToken);

        RefreshToken next= new RefreshToken(current.getUserId(),newHash,current.getFamilyId(),null,ip,Instant.now().plus(REFRESH_TOKEN_EXPIRATION_PERIOD));

        refreshTokenRepository.save(next);

        current.revoke(now);
        current.markReplacedBy(next.getId());

        return new Rotation(current.getUserId(),newRawToken,next.getExpiresAt());
    }

    @Transactional
    public void revoke(String rawToken){
        String hash = RefreshTokens.hash(rawToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(row->{
            row.revoke(Instant.now());
            refreshTokenRepository.revokeFamily(row.getFamilyId(),Instant.now());
        });
    }


    public record Issued(String rawToken, Instant expiresAt) {};
    public record Rotation(UUID userId, String rawToken, Instant expiresAt) {};
}