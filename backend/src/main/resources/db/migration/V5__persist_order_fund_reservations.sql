ALTER TABLE trading_order
    ADD COLUMN reserved_amount NUMERIC(19,4) NOT NULL DEFAULT 0;

UPDATE trading_order
SET reserved_amount = CEILING(limit_price * requested_quantity * 10000) / 10000
WHERE side = 'BUY' AND order_type = 'LIMIT' AND status IN ('PENDING', 'PARTIALLY_FILLED');

ALTER TABLE trading_order
    ADD CONSTRAINT ck_order_reserved_amount CHECK (reserved_amount >= 0);
