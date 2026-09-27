Alter TABLE cart ADD COLUMN checkout_at TIMESTAMPTZ NULL;


-- 1. Change the column type from CHAR(3) to VARCHAR(3)
ALTER TABLE orders ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE products ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payments ALTER COLUMN currency TYPE VARCHAR(3);

-- 2. Add a CHECK constraint to enforce the 3-letter ISO standard
ALTER TABLE orders ADD CONSTRAINT ck_orders_currency_length CHECK (length(currency) = 3);
ALTER TABLE products ADD CONSTRAINT ck_products_currency_length CHECK (length(currency) = 3);
ALTER TABLE payments ADD CONSTRAINT ck_payments_currency_length CHECK (length(currency) = 3);