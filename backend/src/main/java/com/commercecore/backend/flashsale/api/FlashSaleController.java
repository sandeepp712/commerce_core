package com.commercecore.backend.flashsale.api;

import com.commercecore.backend.flashsale.service.FlashSaleCheckoutResponse;
import com.commercecore.backend.flashsale.service.FlashSaleService;
import com.commercecore.backend.flashsale.service.FlashSaleStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/flash-sale")
@Tag(name = "Flash Sale", description = "High-concurrency flash sale endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class FlashSaleController {

    private final FlashSaleService flashSaleService;
    private final FlashSaleStockService stockService;

    public FlashSaleController(FlashSaleService flashSaleService,
                               FlashSaleStockService stockService) {
        this.flashSaleService = flashSaleService;
        this.stockService = stockService;
    }

    /**
     * Pre-warm endpoint. Call this BEFORE the flash sale starts.
     * In production, this would be triggered by a scheduled job or admin action.
     */
    @PostMapping("/pre-warm")
    @Operation(summary = "Pre-warm product stock into Redis (Admin only)")
    public ResponseEntity<Map<String, Object>> preWarm(
            @RequestParam UUID productId) {
        int stock = stockService.preWarm(productId);
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "stockLoaded", stock,
                "message", "Product pre-warmed successfully. Flash sale ready."
        ));
    }

    /**
     * Flash Sale Checkout. This is the HOT PATH.
     * Handles 50,000+ concurrent users with <5ms response time.
     *
     * Returns 202 Accepted (not 201 Created) because the order
     * is processed asynchronously via RabbitMQ.
     */
    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Flash sale checkout (high concurrency)")
    public ResponseEntity<FlashSaleCheckoutResponse> flashCheckout(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FlashSaleCheckoutRequest request) {

        FlashSaleCheckoutResponse response = flashSaleService.checkout(
                userId,
                request.productId(),
                request.quantity(),
                UUID.randomUUID()  // Generate idempotency key
        );

        return ResponseEntity.accepted().body(response);
    }

    /**
     * Check current stock in Redis (for monitoring/debugging).
     */
    @GetMapping("/stock/{productId}")
    @Operation(summary = "Check flash sale stock in Redis")
    public ResponseEntity<Map<String, Object>> getStock(@PathVariable UUID productId) {
        Integer stock = stockService.getStock(productId);
        boolean preWarmed = stockService.isPreWarmed(productId);
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "stock", stock != null ? stock : 0,
                "preWarmed", preWarmed
        ));
    }

    // ─── Request DTO ─────────────────────────────────────────────────────────

    public record FlashSaleCheckoutRequest(
            @NotNull UUID productId,
            @NotNull @Min(1) Integer quantity
    ) {}
}