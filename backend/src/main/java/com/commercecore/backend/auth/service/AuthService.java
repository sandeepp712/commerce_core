package com.commercecore.backend.auth.service;

import com.commercecore.backend.auth.api.dto.LoginResponse;
import com.commercecore.backend.auth.api.dto.RegisterResponse;
import com.commercecore.backend.auth.domain.User;
import com.commercecore.backend.auth.domain.UserRole;
import com.commercecore.backend.auth.repo.UserRepository;
import com.commercecore.backend.shared.config.JwtService;
import com.commercecore.backend.shared.exception.AccountLockedException;
import com.commercecore.backend.shared.exception.EmailAlreadyExistsException;
import com.commercecore.backend.shared.exception.InvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final long ACCESS_TOKEN_TTL = 900;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public RegisterResponse register(String email, String username, String password) {
        // 1. Check if email already exists
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        // 2. Hash the password (NEVER store plain text)
        String hashedPassword = passwordEncoder.encode(password);

        // 3. Create and save user
        User user = new User(email, username, hashedPassword, UserRole.CUSTOMER);
        userRepository.save(user);

        return new RegisterResponse(user.getId(), user.getEmail());
    }

    @Transactional
    public LoginResponse login(String email, String password,String deviceInfo,String ip) {
        // 1. Find user by email
        User user = userRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);

        // 2. Check account lockout before password Verifications
        if (user.getLockedUntil() != null  && user.getLockedUntil().isAfter(Instant.now())) {
            throw new AccountLockedException("Account temporarily locked");
        }

        // 3. Verify password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            // Increment failed attempts
            userRepository.incrementFailedAttempts(email,Instant.now().plusSeconds(900));
            throw new InvalidCredentialsException();
        }

        // 4. Reset failed attempts on success
        user.recordSuccessfulLogin();
        userRepository.save(user);

        // 5. Generate JWT
        String accessToken = jwtService.generateToken(user.getId(), user.getRole().toString());
        RefreshTokenService.Issued issued = refreshTokenService.issueNew(user.getId(),deviceInfo,ip);

        return new LoginResponse(accessToken, issued.rawToken(),ACCESS_TOKEN_TTL ,user.getRole().name());
    }


    @Transactional
    public LoginResponse refreshToken(String refreshToken,String ip) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken,ip);

        User user = userRepository.findById(rotation.userId())
                .orElseThrow(()->new RuntimeException("Not valid id"));

        String newAccessToken = jwtService.generateToken(user.getId(), user.getRole().toString());

        return new LoginResponse(newAccessToken,rotation.rawToken(),ACCESS_TOKEN_TTL,user.getRole().name());
    }

    @Transactional
    public void logout(String accessToken) {
        refreshTokenService.revoke(accessToken);
    }
}