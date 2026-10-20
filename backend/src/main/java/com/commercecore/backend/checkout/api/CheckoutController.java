package com.commercecore.backend.checkout.api;

import com.commercecore.backend.checkout.api.dto.CheckoutRequest;
import com.commercecore.backend.checkout.api.dto.CheckoutResponse;
import com.commercecore.backend.checkout.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name="Checkout",description = "Full checkout with inventory reservation")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }



    @PostMapping
    @Operation(
            summary = "Initiate checkout",
            description = """
                    Creates an order in AWAITING_PAYMENT_CONFIRMATION state.
                    Reserves inventory atomically. Marks cart as CHECKED_OUT.
    
                    Requires Idempotency-Key header for retry safety.
                    Returns 409 if insufficient stock.
                    Returns 422 if cart is empty.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created successfully"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock or idempotency conflict"),
            @ApiResponse(responseCode = "422", description = "Cart is empty or product unavailable")
    })
    public ResponseEntity<CheckoutResponse> processCheckout(
            @AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "IdempotencyKey",required = true) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {

        CheckoutResponse response = checkoutService.processCheckout(userId,idempotencyKey ,request);
//        URI location = URI.create("/api/v1/orders/"+response.orderId());
        return ResponseEntity.accepted().body(response);
    }

}