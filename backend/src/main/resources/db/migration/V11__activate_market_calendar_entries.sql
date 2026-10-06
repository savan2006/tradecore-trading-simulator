ALTER TABLE market_session
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE market_session
    ADD CONSTRAINT ck_market_session_override_times
    CHECK ((opens_at IS NULL AND closes_at IS NULL)
        OR (opens_at IS NOT NULL AND closes_at IS NOT NULL AND opens_at < closes_at));

ALTER TABLE market_session
    ADD CONSTRAINT ck_market_session_holiday_no_override
    CHECK (NOT holiday OR (opens_at IS NULL AND closes_at IS NULL));
