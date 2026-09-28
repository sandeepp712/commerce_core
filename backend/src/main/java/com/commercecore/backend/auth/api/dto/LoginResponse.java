package com.commercecore.backend.auth.api.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String role
) { }