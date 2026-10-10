# TradeCore architecture

These diagrams describe the current code paths. PostgreSQL is authoritative for financial state; Redis is only used for market-data caching and rate limits.

## System architecture

```mermaid
flowchart LR
  User[Browser] --> UI[Next.js App Router]
  UI -->|same-origin API requests| Proxy[Next.js API proxy and session routes]
  Proxy -->|Basic auth on server request| API[Spring Boot feature APIs]
  User -->|WebSocket /ws/market-quotes| WS[MarketQuoteWebSocketHandler]
  API --> Identity[Identity and security]
  API --> Orders[Orders and idempotency]
  API --> Trading[Execution, accounts, positions, ledger]
  API --> Market[Market data and calendar]
  API --> Other[Watchlists, alerts, journal, notifications, admin]
  Identity --> DB[(PostgreSQL)]
  Orders --> DB
  Trading --> DB
  Market --> DB
  Other --> DB
  Market --> Cache[(Redis cache)]
  API --> Limits[(Redis rate limits)]
  Market --> Adapter[MarketDataProvider]
  Adapter --> NSE[NSE MCP over HTTPS]
  Market --> WS
```

## Order lifecycle

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> VALIDATING
  VALIDATING --> REJECTED: validation fails
  VALIDATING --> PENDING: checks pass and reservations recorded
  PENDING --> PENDING: modify with checks and reservation adjustment
  PENDING --> FILLED: full eligible execution
  PENDING --> CANCELLED: owner cancellation or square-off cancellation
  FILLED --> [*]
  CANCELLED --> [*]
  REJECTED --> [*]
```

Buy orders reserve funds and sell orders reserve available position quantity. Market session, quote freshness, risk, ownership, and idempotency are enforced in the request and execution paths. Fills are complete-only; no short selling or partial execution is supported.

## Market-data flow

```mermaid
sequenceDiagram
  participant Job as Quote refresh job
  participant Provider as NSE MCP adapter
  participant Ingest as MarketDataIngestionService
  participant DB as PostgreSQL
  participant Redis
  participant Query as MarketDataQueryService
  participant WS as WebSocket broadcaster
  Job->>Provider: fetch provider quote snapshots
  Provider-->>Ingest: normalized provider-neutral data
  Ingest->>Ingest: validate symbol, values, timestamps, freshness
  Ingest->>DB: persist accepted quote
  Ingest->>Redis: update cache (best effort)
  Ingest->>Query: read persisted quote for update
  Query-->>WS: publish quote after persistence
  WS-->>WS: enqueue bounded sends to subscribed clients
```

The provider adapter is the only NSE integration boundary. Quote freshness is represented as `LIVE`, `STALE`, or `UNAVAILABLE`; stale or unavailable quotes are not eligible to execute an order. The market calendar/session policy is checked separately from provider freshness.

## Main entity relationships

```mermaid
erDiagram
  APP_USER ||--|| TRADING_ACCOUNT : owns
  APP_USER ||--o{ WATCHLIST : creates
  APP_USER ||--o{ NOTIFICATION : receives
  APP_USER ||--o{ PRICE_ALERT : configures
  APP_USER ||--o{ TRADE_JOURNAL : writes
  APP_USER ||--o{ AUDIT_LOG : acts
  TRADING_ACCOUNT ||--o{ TRADING_ORDER : submits
  TRADING_ACCOUNT ||--o{ POSITION : holds
  TRADING_ACCOUNT ||--o{ LEDGER_ENTRY : records
  TRADING_ACCOUNT ||--o{ IDEMPOTENCY_RECORD : scopes
  TRADING_ACCOUNT ||--o{ RISK_LIMIT : configures
  TRADING_ORDER ||--o{ ORDER_EVENT : records
  TRADING_ORDER ||--o{ EXECUTION : fills
  TRADING_ORDER ||--o{ LEDGER_ENTRY : references
  TRADING_ORDER ||--o| TRADE_JOURNAL : reviewed_in
  INSTRUMENT ||--o| MARKET_QUOTE : quoted_by
  INSTRUMENT ||--o{ MARKET_CANDLE : aggregates
  INSTRUMENT ||--o{ TRADING_ORDER : traded
  INSTRUMENT ||--o{ POSITION : held_as
  INSTRUMENT ||--o{ PRICE_ALERT : watched
  WATCHLIST ||--o{ WATCHLIST_ITEM : contains
  WATCHLIST ||--o{ PRICE_ALERT : groups
  INSTRUMENT ||--o| LEARNING_PROFILE : described_by
```

`market_session` is keyed by trading date and has no user relationship. User-owned trading rows connect through `trading_account` or `app_user`; instruments, quotes, candles, learning profiles, and market sessions are shared reference/market data.

## WebSocket flow

```mermaid
sequenceDiagram
  participant Browser
  participant Handler as MarketQuoteWebSocketHandler
  participant Ingest as MarketDataIngestionService
  participant DB as PostgreSQL
  Browser->>Handler: connect with approved Origin
  Browser->>Handler: subscribe {action, symbol}
  Handler->>Handler: validate symbol and client limits
  Ingest->>DB: persist quote
  Ingest->>Handler: publish persisted quote
  Handler->>Handler: enqueue bounded async message for subscribers
  Handler-->>Browser: quote update
```

The handler restricts allowed origins, message size/rate, subscriptions, and connected clients. Sends run through a bounded worker queue and each client has a bounded pending-quote queue. Fan-out is local to one backend process; multiple instances need a shared pub/sub design and scheduler coordination.
