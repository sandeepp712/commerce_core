package com.commercecore.backend.checkout.controller;

import com.commercecore.backend.checkout.api.dto.CheckoutRequest;
import com.commercecore.backend.checkout.api.dto.CheckoutResponse;
import com.commercecore.backend.checkout.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> processCheckout(
            @AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "IdempotencyKey",required = true) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {

        CheckoutResponse response = checkoutService.processCheckout(userId,idempotencyKey ,request);
//        URI location = URI.create("/api/v1/orders/"+response.orderId());
        return ResponseEntity.accepted().body(response);
    }

}