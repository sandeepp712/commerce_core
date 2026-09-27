package com.commercecore.backend.shared.error;

import java.time.Instant;

public record ErrorResponse(
        String errorCode,
        String message,
        int status,
        String path,
        Instant timestamp
) {}