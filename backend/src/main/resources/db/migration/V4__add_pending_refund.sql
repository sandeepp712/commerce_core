-- ============================================================
-- pending_refunds
-- Pattern B: transactional refund intent + background worker.
--
-- The webhook transaction INSERTs a row here and commits.
-- A worker OUTSIDE the transaction reads PENDING rows and
-- calls the provider. Crash-safe: the row survives restart.
-- ============================================================
CREATE TABLE pending_refunds (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders(id)   ON DELETE RESTRICT,
    payment_id   UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    provider_intent_id TEXT NOT NULL,
    amount             NUMERIC(12,2) NOT NULL,
    currency           VARCHAR(3)       NOT NULL DEFAULT 'INR',
    status             TEXT          NOT NULL DEFAULT 'PENDING',
    retry_count        INTEGER       NOT NULL DEFAULT 0,
    last_error         TEXT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,

    CONSTRAINT ck_refunds_status  CHECK (status IN ('PENDING','COMPLETED','FAILED')),

    CONSTRAINT ck_pending_refunds_amount_nonneg
        CHECK (amount >= 0),

    CONSTRAINT ck_pending_refunds_retry_nonneg
        CHECK (retry_count >= 0)
);

-- Worker poll: the hottest query is "give me PENDING rows, oldest first".
-- Partial index — only rows the worker will ever look at are indexed.
CREATE INDEX idx_pending_refunds_pending
    ON pending_refunds (created_at ASC)
    WHERE status = 'PENDING';

-- Reconciliation and admin lookups.
CREATE INDEX idx_pending_refunds_order   ON pending_refunds (order_id);
CREATE INDEX idx_pending_refunds_status  ON pending_refunds (status);