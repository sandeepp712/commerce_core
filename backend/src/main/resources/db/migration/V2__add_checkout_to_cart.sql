Alter TABLE cart ADD COLUMN checkout_at TIMESTAMPTZ NULL;


-- 1. Change the column type from CHAR(3) to VARCHAR(3)
ALTER TABLE orders ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE products ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payments ALTER COLUMN currency TYPE VARCHAR(3);

-- 2. Add a CHECK constraint to enforce the 3-letter ISO standard
ALTER TABLE orders ADD CONSTRAINT ck_orders_currency_length CHECK (length(currency) = 3);
ALTER TABLE products ADD CONSTRAINT ck_products_currency_length CHECK (length(currency) = 3);
ALTER TABLE payments ADD CONSTRAINT ck_payments_currency_length CHECK (length(currency) = 3);




-- V3__add_auth_columns.sql
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;