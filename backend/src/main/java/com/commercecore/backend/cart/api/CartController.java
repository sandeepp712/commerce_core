package com.commercecore.backend.cart.api;

import com.commercecore.backend.cart.api.dto.AddCartItemRequest;
import com.commercecore.backend.cart.api.dto.CartResponse;
import com.commercecore.backend.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart", description = "Shopping cart management")
@SecurityRequirement(name = "Bearer Authentication")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Get the current user's active cart")
    public ResponseEntity<CartResponse> getCart(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @PostMapping("/items")
    @Operation(summary = "Add or update an item in the cart")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody AddCartItemRequest request) {
        return ResponseEntity.ok(cartService.addItem(userId, request));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove an item from the cart")
    public ResponseEntity<CartResponse> removeItem(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID productId) {
        return ResponseEntity.ok(cartService.removeItem(userId, productId));
    }

    @PostMapping("/clear")
    @Operation(summary = "Clear all items from the cart")
    public ResponseEntity<Void> clearCart(@AuthenticationPrincipal UUID userId) {
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }
}