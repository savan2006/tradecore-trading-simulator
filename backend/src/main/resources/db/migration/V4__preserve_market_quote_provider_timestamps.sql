ALTER TABLE market_quote
    ADD COLUMN provider_updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE market_quote
    DROP CONSTRAINT ck_market_quote_market_status;

ALTER TABLE market_quote
    ADD CONSTRAINT ck_market_quote_market_status
        CHECK (market_status IN ('PRE_OPEN', 'OPEN', 'SQUARE_OFF_WINDOW', 'CLOSED', 'HOLIDAY', 'UNKNOWN'));
