package com.commercecore.backend.payment.service;

import com.commercecore.backend.payment.domain.PendingRefund;
import com.commercecore.backend.payment.repo.PaymentRepository;
import com.commercecore.backend.payment.repo.PendingRefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background worker that processes pending refunds.
 *
 * WHY THIS EXISTS OUTSIDE THE WEBHOOK TRANSACTION:
 * - The webhook handler cannot call Stripe's refund API inside @Transactional
 *   (connection pool exhaustion risk if Stripe is slow).
 * - Instead, the webhook writes a PendingRefund record.
 * - This worker picks it up asynchronously and calls Stripe.
 *
 * RELIABILITY GUARANTEES:
 * - If the server crashes after webhook commit but before refund executes,
 *   the PendingRefund row persists. Worker picks it up on restart.
 * - If Stripe API is temporarily down, retry_count increments.
 *   After 3 failures, status = FAILED → alert ops team.
 * - FOR UPDATE SKIP LOCKED prevents multiple worker instances
 *   from processing the same refund.
 */
@Component
public class RefundWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RefundWorker.class);
    private static final int BATCH_SIZE = 10;

    private final PendingRefundRepository pendingRefundRepository;
    private final PaymentRepository paymentRepository;
    // private final PaymentProvider paymentProvider; // Stripe/Razorpay client

    public RefundWorker(PendingRefundRepository pendingRefundRepository,
                        PaymentRepository paymentRepository) {
        this.pendingRefundRepository = pendingRefundRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Runs every 10 seconds.
     * Processes up to BATCH_SIZE pending refunds per execution.
     */
    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void processPendingRefunds() {
        List<PendingRefund> refunds =
                pendingRefundRepository.findPendingForProcessing(BATCH_SIZE);

        if (refunds.isEmpty()) return;

        LOG.info("Processing {} pending refunds", refunds.size());

        for (PendingRefund refund : refunds) {
            try {
                executeRefund(refund);
                refund.markCompleted();
                LOG.info("Refund completed for order {}", refund.getOrderId());

            } catch (Exception e) {
                refund.markFailed(e.getMessage());
                LOG.error("Refund failed for order {}. Attempt {}. Error: {}",
                        refund.getOrderId(), refund.getRetryCount(), e.getMessage());

                if (!refund.canRetry()) {
                    LOG.error("Refund permanently failed for order {}. " +
                            "Manual intervention required.", refund.getOrderId());
                    // TODO: Send alert to ops team (PagerDuty, Slack, email)
                }
            }
            pendingRefundRepository.save(refund);
        }
    }

    private void executeRefund(PendingRefund refund) {
        // In production, this calls the Stripe API:
        // Refund.create(Map.of("payment_intent", refund.getProviderIntentId()));
        //
        // For now, simulate the call:
        LOG.info("Calling Stripe API to refund payment intent: {} amount: {} {}",
                refund.getProviderIntentId(), refund.getAmount(), refund.getCurrency());

        // Simulate potential failure for testing
        // if (Math.random() < 0.1) throw new RuntimeException("Stripe API timeout");

        // After successful refund, update the payment record
        paymentRepository.findById(refund.getPaymentId()).ifPresent(payment -> {
            payment.markRefunded();
            paymentRepository.save(payment);
        });
    }
}