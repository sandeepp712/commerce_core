-- V1__schema.sql
-- Commerce Core — initial schema
-- v1 monolith: one Postgres, each module owns its tables.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ============================================================
-- users
-- ============================================================
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL,
    username      TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'CUSTOMER',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT    uq_users_email UNIQUE (email),
    CONSTRAINT    ck_users_role CHECK (role IN ('CUSTOMER','ADMIN','SUPPORT'))
);


-- ============================================================
-- products
-- ============================================================
CREATE TABLE products (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku         TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT,
    price       NUMERIC(12,2) NOT NULL ,
    currency    CHAR(3) NOT NULL DEFAULT 'INR',
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT  uq_products_sku     UNIQUE (sku),
    CONSTRAINT  ck_products_price_nonneg CHECK ( price>=0 )
);


-- ============================================================
-- inventory
-- product_id is the PK: this is the "PK-for-reserve" index.
-- available = on_hand - reserved, and it can never go negative.
-- ============================================================
CREATE TABLE inventory (
    product_id UUID PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    on_hand    INTEGER NOT NULL DEFAULT 0,
    reserved   INTEGER NOT NULL DEFAULT 0,
    version    BIGINT  NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_inventory_on_hand_nonneg    CHECK (on_hand  >= 0),
    CONSTRAINT ck_inventory_reserved_nonneg   CHECK (reserved >= 0),
    CONSTRAINT ck_inventory_available_nonneg  CHECK (on_hand - reserved >= 0)
);


-- ============================================================
-- addresses
-- ============================================================
CREATE TABLE addresses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    line1       TEXT NOT NULL,
    line2       TEXT,
    city        TEXT NOT NULL,
    postal_code TEXT NOT NULL,
    country     TEXT NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_addresses_user ON addresses (user_id);



-- ============================================================
-- orders
-- Row exists at checkout initiation, state =
-- AWAITING_PAYMENT_CONFIRMATION, before any money moves.
-- ============================================================
CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id),
    state               TEXT NOT NULL,
    subtotal            NUMERIC(12,2) NOT NULL,
    shipping            NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    total               NUMERIC(12,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'INR',
    shipping_address    JSONB NOT NULL,
    idempotency_key     TEXT NOT NULL,
    expires_at          TIMESTAMPTZ,          -- TTL for AWAITING_PAYMENT_CONFIRMATION
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_orders_state CHECK (state IN (
        'AWAITING_PAYMENT_CONFIRMATION',
        'PAID',
        'SHIPPED',
        'DELIVERED',
        'CANCELLED',
        'EXPIRED',
        'REFUNDED'
    )),
    CONSTRAINT ck_orders_money_nonneg CHECK (
        subtotal >= 0 AND shipping >= 0 AND tax >= 0 AND total >= 0
    )
);

-- Index 3 of 3: (user_id, created_at DESC)
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_state_expires ON orders (state, expires_at)
    WHERE state = 'AWAITING_PAYMENT_CONFIRMATION';



-- ============================================================
-- order_items
-- ============================================================
CREATE TABLE order_items (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    qty        INTEGER NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL,
    line_total NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_qty_pos        CHECK (qty > 0),
    CONSTRAINT ck_order_items_money_nonneg   CHECK (unit_price >= 0 AND line_total >= 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_product ON order_items (product_id);


-- ============================================================
-- inventory_reservations
-- The hold. UNIQUE(order_id, product_id) = one hold per line.
-- ============================================================
CREATE TABLE inventory_reservations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id   UUID NOT NULL REFERENCES products(id),
    qty          INTEGER NOT NULL,
    status       TEXT NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    released_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservations_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_reservations_qty_pos       CHECK (qty > 0),
    CONSTRAINT ck_reservations_status        CHECK (status IN ('HELD','CONFIRMED','RELEASED'))
);
-- Index 2 of 3: partial TTL index — only HELD rows are reaped.
CREATE INDEX idx_reservations_expires_held
    ON inventory_reservations (expires_at)
    WHERE status = 'HELD';
CREATE INDEX idx_reservations_product ON inventory_reservations (product_id);
CREATE INDEX idx_reservations_order ON inventory_reservations (order_id);


-- ============================================================
-- cart
-- Bucket for user to add product
-- ============================================================
CREATE TABLE cart(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cart_status CHECK (status IN ('ACTIVE','CHECKED_OUT','ABANDONED'))
);
CREATE UNIQUE INDEX uq_cart_user_active ON cart (user_id) WHERE status = 'ACTIVE';

CREATE TABLE cart_item(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id     UUID NOT NULL REFERENCES cart(id) ON DELETE CASCADE,
    product_id  UUID NOT NULL REFERENCES products(id),
    qty         INTEGER NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_item_cart_product UNIQUE (cart_id,product_id),
    CONSTRAINT ck_cart_item_qty_pos     CHECK ( qty>0 ),
    CONSTRAINT ck_cart_item_price_nonneg    CHECK ( unit_price >= 0 )
);

CREATE INDEX idx_cart_item_cart ON cart_item (cart_id);



-- ============================================================
-- payments
-- One order, many attempts. provider_event_id dedups webhooks.
-- ============================================================
CREATE TABLE payments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id           UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    provider           TEXT NOT NULL,
    provider_intent_id TEXT NOT NULL,
    provider_event_id  TEXT,                    -- nullable; UNIQUE allows many NULLs
    attempt_no         INTEGER NOT NULL DEFAULT 1,
    amount             NUMERIC(12,2) NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'INR',
    status             TEXT NOT NULL,
    raw_payload        JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_provider_event UNIQUE (provider_event_id),
    CONSTRAINT uq_payments_intent         UNIQUE (provider, provider_intent_id),
    CONSTRAINT ck_payments_status CHECK (status IN (
        'CREATED','REQUIRES_ACTION','AUTHORIZED','CAPTURED','FAILED','REFUNDED'
    )),
    CONSTRAINT ck_payments_attempt_pos     CHECK (attempt_no > 0),
    CONSTRAINT ck_payments_amount_nonneg   CHECK (amount >= 0)
);
CREATE INDEX idx_payments_order ON payments (order_id);



-- ============================================================
-- order_events
-- State is a condition (now); events are moments (then).
-- ============================================================
CREATE TABLE order_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    from_state TEXT,
    to_state   TEXT,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_events_order_created ON order_events (order_id, created_at DESC);


-- ============================================================
-- shipments
-- ============================================================
CREATE TABLE shipments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    carrier         TEXT,
    tracking_number TEXT,
    status          TEXT NOT NULL,
    shipped_at      TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipments_status CHECK (status IN (
        'PENDING','SHIPPED','IN_TRANSIT','DELIVERED','FAILED'
    ))
);
CREATE INDEX idx_shipments_order ON shipments (order_id);


-- ============================================================
-- returns
-- Post-fulfilment path. Order reaches REFUNDED only from here.
-- ============================================================
CREATE TABLE returns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID NOT NULL REFERENCES orders(id),
    user_id       UUID NOT NULL REFERENCES users(id),
    status        TEXT NOT NULL,
    reason        TEXT,
    refund_amount NUMERIC(12,2),
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at   TIMESTAMPTZ,
    received_at   TIMESTAMPTZ,
    CONSTRAINT ck_returns_status CHECK (status IN (
        'REQUESTED','IN_TRANSIT','RECEIVED','APPROVED','REJECTED'
    )),
    CONSTRAINT ck_returns_refund_nonneg CHECK (
        refund_amount IS NULL OR refund_amount >= 0
    )
);
CREATE INDEX idx_returns_order ON returns (order_id);
CREATE INDEX idx_returns_user ON returns (user_id);


-- ============================================================
-- reviews
-- ============================================================
CREATE TABLE reviews (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    order_id   UUID REFERENCES orders(id),
    rating     SMALLINT NOT NULL,
    title      TEXT,
    body       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reviews_user_product UNIQUE (user_id, product_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX idx_reviews_product_created ON reviews (product_id, created_at DESC);


CREATE TABLE outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type   TEXT NOT NULL,
    payload      JSONB NOT NULL,
    published    BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at)
    WHERE published = false;


CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


CREATE TRIGGER trg_users_updated     BEFORE UPDATE ON users     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_products_updated  BEFORE UPDATE ON products  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_inventory_updated BEFORE UPDATE ON inventory FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_orders_updated    BEFORE UPDATE ON orders    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_payments_updated  BEFORE UPDATE ON payments  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_cart_updated      BEFORE UPDATE ON cart      FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_cart_item_updated BEFORE UPDATE ON cart_item FOR EACH ROW EXECUTE FUNCTION set_updated_at();