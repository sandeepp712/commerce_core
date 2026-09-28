package com.commercecore.backend.shared.error;

import com.commercecore.backend.shared.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;


@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<ErrorResponse> handleEmptyCartException(EmptyCartException ex, HttpServletRequest request) {
        LOG.warn("Empty cart access at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                "UNPROCESSABLE_ENTITY",
                ex.getMessage(),
                HttpStatus.UNPROCESSABLE_CONTENT.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> idempotencyConflictException(IdempotencyConflictException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "CONFLICT",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStockException(InsufficientStockException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "CONFLICT",
                "Insufficient Stock",
                HttpStatus.CONFLICT.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(IllegalOrderTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalOrderTransitionException(IllegalOrderTransitionException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "CONFLICT",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ProductUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleProductUnavailableException(ProductUnavailableException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "UNPROCESSABLE_ENTITY",
                ex.getMessage(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.UNPROCESSABLE_CONTENT);
    }


    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(InvalidCredentialsException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "UNAUTHORIZED",
                ex.getMessage(),
                HttpStatus.UNAUTHORIZED.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExistsException(EmailAlreadyExistsException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "CONFLICT",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLockedException(AccountLockedException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                "LOCKED",
                ex.getMessage(),
                HttpStatus.LOCKED.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.LOCKED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled exception occurred at {}: ", request.getRequestURI(), ex);
        ErrorResponse error = new ErrorResponse(
                "Internal server Error",
                "An unexcepted internal error occurred. Please try again later.",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                request.getRequestURI(),
                Instant.now()
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}