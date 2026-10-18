package com.commercecore.backend.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CheckoutMetrics {

    private final Counter checkoutSuccess;
    private final Counter checkoutFailure;
    private final Counter insufficientStock;
    private final Counter duplicateWebhook;
    private final Timer checkoutDuration;

    public CheckoutMetrics(MeterRegistry registry) {
        this.checkoutSuccess = Counter.builder("checkout.success.total")
                .description("Total successful checkouts")
                .register(registry);

        this.checkoutFailure = Counter.builder("checkout.failure.total")
                .description("Total failed checkouts")
                .register(registry);

        this.insufficientStock = Counter.builder("checkout.insufficient_stock.total")
                .description("Checkouts rejected due to insufficient stock")
                .register(registry);

        this.duplicateWebhook = Counter.builder("webhook.duplicate.total")
                .description("Duplicate webhook events absorbed")
                .register(registry);

        this.checkoutDuration = Timer.builder("checkout.duration.seconds")
                .description("Time to process a checkout")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordSuccess(long durationMs) {
        checkoutSuccess.increment();
        checkoutDuration.record(Duration.ofMillis(durationMs));
    }

    public void recordFailure() {
        checkoutFailure.increment();
    }

    public void recordInsufficientStock() {
        insufficientStock.increment();
    }

    public void recordDuplicateWebhook() {
        duplicateWebhook.increment();
    }
}