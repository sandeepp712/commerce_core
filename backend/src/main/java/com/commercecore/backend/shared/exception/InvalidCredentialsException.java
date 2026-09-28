package com.commercecore.backend.shared.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException()
    {
        super("Invalid username or password");
    }
}