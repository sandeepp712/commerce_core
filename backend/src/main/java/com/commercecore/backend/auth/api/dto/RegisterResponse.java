package com.commercecore.backend.auth.api.dto;

import java.util.UUID;

public record RegisterResponse(UUID userId,String email) { }