package com.commercecore.backend.checkout.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CheckoutRequest(
		@NotNull(message = "Shipping address is required")
		UUID shippingAddressId,
		String idempotencyKey,
		String couponCode
){}
