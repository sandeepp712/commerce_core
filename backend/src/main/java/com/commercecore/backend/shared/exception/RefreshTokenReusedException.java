package com.commercecore.backend.shared.exception;

public class RefreshTokenReusedException extends RuntimeException {
    public RefreshTokenReusedException() {
    }
    public RefreshTokenReusedException(String message) {
        super(message);
    }
}