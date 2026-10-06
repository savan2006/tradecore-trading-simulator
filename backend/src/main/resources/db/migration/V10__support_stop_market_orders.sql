ALTER TABLE trading_order
    ADD COLUMN trigger_price NUMERIC(19,6);

ALTER TABLE trading_order
    DROP CONSTRAINT ck_order_type;

ALTER TABLE trading_order
    ADD CONSTRAINT ck_order_type CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP_MARKET'));

ALTER TABLE trading_order
    DROP CONSTRAINT ck_order_limit_price;

ALTER TABLE trading_order
    ADD CONSTRAINT ck_order_limit_price CHECK (
        (order_type = 'LIMIT' AND limit_price > 0 AND trigger_price IS NULL)
        OR (order_type = 'MARKET' AND limit_price IS NULL AND trigger_price IS NULL)
        OR (order_type = 'STOP_MARKET' AND limit_price IS NULL AND trigger_price > 0)
    );
