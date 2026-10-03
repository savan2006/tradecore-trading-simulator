CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE trading_account (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    status VARCHAR(20) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    available_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_trading_account_status CHECK (status IN ('ACTIVE', 'RESTRICTED', 'CLOSED')),
    CONSTRAINT ck_trading_account_balances CHECK (available_balance >= 0 AND reserved_balance >= 0)
);

CREATE TABLE instrument (
    id UUID PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    company_name VARCHAR(160) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    instrument_type VARCHAR(24) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    tradable BOOLEAN NOT NULL DEFAULT TRUE,
    provider_instrument_key VARCHAR(160),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_instrument_exchange_symbol UNIQUE (exchange, symbol),
    CONSTRAINT uq_instrument_provider_key UNIQUE (provider_instrument_key),
    CONSTRAINT ck_instrument_type CHECK (instrument_type IN ('EQUITY'))
);

CREATE TABLE market_quote (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL UNIQUE REFERENCES instrument(id),
    last_price NUMERIC(19,6),
    previous_close NUMERIC(19,6),
    open_price NUMERIC(19,6),
    high_price NUMERIC(19,6),
    low_price NUMERIC(19,6),
    volume BIGINT,
    bid_price NUMERIC(19,6),
    bid_quantity BIGINT,
    ask_price NUMERIC(19,6),
    ask_quantity BIGINT,
    market_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE,
    market_status VARCHAR(24) NOT NULL,
    data_status VARCHAR(24) NOT NULL,
    CONSTRAINT ck_market_quote_prices CHECK (last_price IS NULL OR last_price >= 0),
    CONSTRAINT ck_market_quote_range CHECK (high_price IS NULL OR low_price IS NULL OR high_price >= low_price),
    CONSTRAINT ck_market_quote_data_status CHECK (data_status IN ('LIVE', 'STALE', 'UNAVAILABLE')),
    CONSTRAINT ck_market_quote_market_status CHECK (market_status IN ('PRE_OPEN', 'OPEN', 'SQUARE_OFF_WINDOW', 'CLOSED', 'HOLIDAY'))
);

CREATE TABLE market_candle (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    resolution VARCHAR(12) NOT NULL,
    bucket_start TIMESTAMP WITH TIME ZONE NOT NULL,
    open_price NUMERIC(19,6) NOT NULL,
    high_price NUMERIC(19,6) NOT NULL,
    low_price NUMERIC(19,6) NOT NULL,
    close_price NUMERIC(19,6) NOT NULL,
    volume BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_market_candle_bucket UNIQUE (instrument_id, resolution, bucket_start),
    CONSTRAINT ck_market_candle_resolution CHECK (resolution IN ('1M', '5M', '15M', '1H', '1D')),
    CONSTRAINT ck_market_candle_prices CHECK (low_price >= 0 AND high_price >= low_price AND open_price >= low_price AND open_price <= high_price AND close_price >= low_price AND close_price <= high_price),
    CONSTRAINT ck_market_candle_volume CHECK (volume >= 0)
);
CREATE INDEX ix_market_candle_history ON market_candle (instrument_id, resolution, bucket_start DESC);

CREATE TABLE market_session (
    id UUID PRIMARY KEY,
    trading_date DATE NOT NULL UNIQUE,
    session_state VARCHAR(24) NOT NULL,
    opens_at TIMESTAMP WITH TIME ZONE,
    closes_at TIMESTAMP WITH TIME ZONE,
    square_off_at TIMESTAMP WITH TIME ZONE,
    holiday BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(240),
    CONSTRAINT ck_market_session_state CHECK (session_state IN ('PRE_OPEN', 'OPEN', 'SQUARE_OFF_WINDOW', 'CLOSED', 'HOLIDAY'))
);

CREATE TABLE trading_order (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES trading_account(id),
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(12) NOT NULL,
    trading_mode VARCHAR(12) NOT NULL,
    requested_quantity BIGINT NOT NULL,
    executed_quantity BIGINT NOT NULL DEFAULT 0,
    remaining_quantity BIGINT NOT NULL,
    limit_price NUMERIC(19,6),
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_order_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_order_type CHECK (order_type IN ('MARKET', 'LIMIT')),
    CONSTRAINT ck_order_mode CHECK (trading_mode IN ('DELIVERY', 'INTRADAY')),
    CONSTRAINT ck_order_status CHECK (status IN ('CREATED', 'VALIDATING', 'ACCEPTED', 'PENDING', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_order_quantities CHECK (requested_quantity > 0 AND executed_quantity >= 0 AND remaining_quantity >= 0 AND executed_quantity + remaining_quantity = requested_quantity),
    CONSTRAINT ck_order_limit_price CHECK ((order_type = 'LIMIT' AND limit_price > 0) OR (order_type = 'MARKET' AND limit_price IS NULL))
);
CREATE INDEX ix_order_account_created ON trading_order (account_id, created_at DESC);
CREATE INDEX ix_order_instrument_status ON trading_order (instrument_id, status);

CREATE TABLE order_event (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES trading_order(id),
    previous_state VARCHAR(24),
    new_state VARCHAR(24) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_order_event_order_time ON order_event (order_id, occurred_at);

CREATE TABLE execution (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES trading_order(id),
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    side VARCHAR(8) NOT NULL,
    trading_mode VARCHAR(12) NOT NULL,
    quantity BIGINT NOT NULL,
    price NUMERIC(19,6) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    market_price NUMERIC(19,6),
    market_at TIMESTAMP WITH TIME ZONE,
    reference_metadata TEXT,
    fee NUMERIC(19,4) NOT NULL DEFAULT 0,
    CONSTRAINT ck_execution_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_execution_mode CHECK (trading_mode IN ('DELIVERY', 'INTRADAY')),
    CONSTRAINT ck_execution_amounts CHECK (quantity > 0 AND price > 0 AND fee >= 0)
);
CREATE INDEX ix_execution_order_time ON execution (order_id, executed_at);

CREATE TABLE position (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES trading_account(id),
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    trading_mode VARCHAR(12) NOT NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    reserved_quantity BIGINT NOT NULL DEFAULT 0,
    average_price NUMERIC(19,6) NOT NULL DEFAULT 0,
    realized_pnl NUMERIC(19,4) NOT NULL DEFAULT 0,
    reference_price NUMERIC(19,6),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_position_account_instrument_mode UNIQUE (account_id, instrument_id, trading_mode),
    CONSTRAINT ck_position_mode CHECK (trading_mode IN ('DELIVERY', 'INTRADAY')),
    CONSTRAINT ck_position_quantities CHECK (quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity <= quantity),
    CONSTRAINT ck_position_average CHECK (average_price >= 0)
);

CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES trading_account(id),
    entry_type VARCHAR(24) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    order_id UUID REFERENCES trading_order(id),
    execution_id UUID REFERENCES execution(id),
    description VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ledger_type CHECK (entry_type IN ('INITIAL_DEPOSIT', 'TRADE_DEBIT', 'TRADE_CREDIT', 'FEE', 'REVERSAL', 'ADJUSTMENT')),
    CONSTRAINT ck_ledger_nonzero_amount CHECK (amount <> 0),
    CONSTRAINT ck_ledger_reference CHECK (order_id IS NOT NULL OR execution_id IS NOT NULL OR entry_type IN ('INITIAL_DEPOSIT', 'ADJUSTMENT'))
);
CREATE INDEX ix_ledger_account_time ON ledger_entry (account_id, occurred_at DESC);

CREATE TABLE risk_limit (
    id UUID PRIMARY KEY,
    account_id UUID REFERENCES trading_account(id),
    instrument_id UUID REFERENCES instrument(id),
    scope VARCHAR(16) NOT NULL,
    limit_type VARCHAR(32) NOT NULL,
    limit_value NUMERIC(19,4) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMP WITH TIME ZONE,
    effective_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_risk_scope CHECK (scope IN ('GLOBAL', 'ACCOUNT', 'INSTRUMENT')),
    CONSTRAINT ck_risk_value CHECK (limit_value > 0),
    CONSTRAINT ck_risk_owner CHECK ((scope = 'GLOBAL' AND account_id IS NULL AND instrument_id IS NULL) OR (scope = 'ACCOUNT' AND account_id IS NOT NULL AND instrument_id IS NULL) OR (scope = 'INSTRUMENT' AND account_id IS NULL AND instrument_id IS NOT NULL)),
    CONSTRAINT ck_risk_effective_period CHECK (effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from)
);
CREATE INDEX ix_risk_limit_account ON risk_limit (account_id, enabled);
CREATE INDEX ix_risk_limit_instrument ON risk_limit (instrument_id, enabled);

CREATE TABLE watchlist (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_watchlist_user_name UNIQUE (user_id, name)
);

CREATE TABLE watchlist_item (
    id UUID PRIMARY KEY,
    watchlist_id UUID NOT NULL REFERENCES watchlist(id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES instrument(id),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_watchlist_item_instrument UNIQUE (watchlist_id, instrument_id),
    CONSTRAINT ck_watchlist_item_order CHECK (sort_order >= 0)
);
CREATE INDEX ix_watchlist_item_order ON watchlist_item (watchlist_id, sort_order);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    notification_type VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_notification_user_unread ON notification (user_id, created_at DESC);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES app_user(id),
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(48) NOT NULL,
    entity_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata TEXT
);
CREATE INDEX ix_audit_entity_time ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_actor_time ON audit_log (actor_user_id, occurred_at DESC);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES trading_account(id),
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    state VARCHAR(20) NOT NULL,
    original_order_id UUID UNIQUE REFERENCES trading_order(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_idempotency_account_key UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);
