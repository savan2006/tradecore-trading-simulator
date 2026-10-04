CREATE TABLE price_alert (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    watchlist_id UUID NOT NULL REFERENCES watchlist(id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    condition VARCHAR(8) NOT NULL,
    target_price NUMERIC(19, 6) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_price_alert_condition CHECK (condition IN ('ABOVE', 'BELOW')),
    CONSTRAINT ck_price_alert_target CHECK (target_price > 0),
    CONSTRAINT ck_price_alert_trigger_state CHECK ((active AND triggered_at IS NULL) OR (NOT active))
);
CREATE INDEX ix_price_alert_active ON price_alert (active, instrument_id);
CREATE INDEX ix_price_alert_user ON price_alert (user_id, created_at DESC);
