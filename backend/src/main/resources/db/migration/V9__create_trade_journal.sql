CREATE TABLE trade_journal (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    order_id UUID NOT NULL UNIQUE REFERENCES trading_order(id),
    thesis TEXT NOT NULL,
    strategy_tag VARCHAR(80),
    went_well TEXT,
    went_wrong TEXT,
    lesson_learned TEXT,
    rating SMALLINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_trade_journal_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);

CREATE INDEX ix_trade_journal_user_created ON trade_journal (user_id, created_at DESC, id DESC);
