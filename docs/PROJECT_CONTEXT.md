# TradeCore - project context

Background for any new session. Verified against the repository on 9 Oct 2026
(Spring Boot 3.5.x, Java 21, Flyway V1-V12). If the code and this file disagree,
the CODE wins - and update this file. Task status lives in `docs/PROGRESS.md`.

## 1. What it is
Educational virtual trading and investing platform. REAL NSE market data,
SIMULATED money (INR 100,000 virtual per user), orders, execution, portfolio, P&L.
No brokerage, bank, payment or real trade. Learning loop:
learn -> study company -> compare -> backtest -> paper trade -> review -> journal.

## 2. Stack and layout
- Backend `backend/`: Java 21, Spring Boot 3.5.x (upgrade to 4.1.x planned, see PROGRESS),
  Maven, Spring Security (HTTP Basic, BCrypt), Spring Data JPA, PostgreSQL (Neon),
  Flyway, Redis Cloud, WebSocket, Actuator, MCP Java SDK 2.0.1 (NSE MCP).
- Frontend `frontend/`: Next.js 16 App Router, React 19, TypeScript. Central API client
  `src/lib/api.ts`, auth context `src/lib/auth-context.tsx`, query hook
  `src/lib/use-api-query.ts`, shared states `src/components/page-states.tsx`,
  shell `src/components/app-shell.tsx`, backend proxy route
  `src/app/api/backend/[...path]/route.ts`.
- Local toolchain: JDK 21 at `C:\Users\DELL\.jdks\ms-21.0.12.1`,
  Maven at `E:\Maven\apache-maven-3.9.16`. No Docker for local development.
- Backend packages (feature based): account, admin, alert, audit, execution,
  foundation (health, security), idempotency, identity, journal, learning, ledger,
  market (and market/nse), notification, order, performance, portfolio, risk,
  strategylab, watchlist.
- Tests: JUnit + MockMvc on H2 (PostgreSQL mode) with mocked providers. Live NSE tests
  are opt-in. H2 tests do not prove PostgreSQL row-lock behaviour.

## 3. Financial model (PostgreSQL = source of truth)
- One account per user: ACTIVE, INR, available 100000, reserved 0, plus an
  INITIAL_DEPOSIT ledger entry (positive). Ledger: credits positive, debits negative.
- Orders: types MARKET, LIMIT, STOP_MARKET; sides BUY, SELL; modes DELIVERY, INTRADAY.
  Lifecycle PENDING -> FILLED or CANCELLED. Only complete fills. No short selling.
- Placement does not execute: it validates (ownership, instrument, quote freshness,
  session, risk, funds/quantity), reserves (BUY reserves money, SELL reserves
  sellable quantity = quantity - reserved quantity), creates the order and an
  ORDER_PLACED event. Only PENDING orders can be modified (LIMIT: quantity/price;
  STOP_MARKET: quantity/trigger) or cancelled (reservation released).
- Execution price: BUY ask else LTP, SELL bid else LTP (ingestion currently stores no
  bid/ask, so LTP is used). STOP_MARKET triggers: BUY when price >= trigger, SELL when
  price <= trigger, then executes as MARKET.
- Settlement is one transaction across Execution, Account, Position, Ledger, Order,
  OrderEvent, reservation. Locks: order -> account -> position (PostgreSQL row locks).
  Idempotency records exist for order placement.
- INTRADAY: pending intraday orders are cancelled and open positions squared off by
  system SELL MARKET INTRADAY orders near session end, only with a fresh quote.
- Portfolio: invested = avg cost x qty; market value = LTP x qty; unrealized =
  (LTP - avg) x qty; realized stays available; no price -> unavailable (never fake).
- Risk limit types: TRADING_DISABLED, INSTRUMENT_BLOCKED, MAX_ORDER_QUANTITY,
  MAX_ORDER_AMOUNT, MAX_ORDER_VALUE. Unknown active type fails closed.
- Audit log is append-only, written after commit, failures isolated.

## 4. Market data
- Provider: NSE MCP (CM Market MCP for current quotes, Bhavcopy MCP for history)
  behind `MarketDataProvider`; Streamable HTTP; normalized, provider-neutral data.
- Persisted in PostgreSQL: MarketQuote (one row per instrument, idempotent update) and
  MarketCandle (daily; unique instrument + resolution + bucket_start).
- Timestamps kept separate: provider_updated_at (crawler time), market_at, received_at.
- Freshness: LIVE / STALE / UNAVAILABLE; a quote older than ~10 minutes is not
  eligible for execution, alerts or other live decisions.
- Schedulers: quote refresh every 5 min inside the regular session
  (Mon-Fri 09:15 inclusive to 15:30 exclusive, Asia/Kolkata); daily candles 18:00 IST.
- Calendar: table market_session (V11, V12). Rules: special-session override ->
  holiday -> normal weekday -> weekend. 16 official 2026 NSE holidays seeded (V12).
  Muhurat session on Sun 2026-11-08 is NOT seeded: add through the admin calendar API
  once NSE publishes the times; never invent them.
- Historical backfill (admin): default 12 months, max 36, 3-month provider windows,
  serial and idempotent.
- Redis: quote cache TTL 15 s, instrument cache TTL 1 h (fall back to PostgreSQL).
  Rate limits: register 5/h per IP, place order 20/min per user, cancel 30/min per user;
  429 + Retry-After; Redis failure fails open.
- WebSocket `/ws/market-quotes` (public market data only): messages subscribe /
  unsubscribe; replies subscribed / unsubscribed / error / quote. Single instance,
  no Redis pub/sub.

## 5. Supported universe (exactly 80 NSE instruments, V3 + learning profiles V7/V8)
Banks: HDFCBANK ICICIBANK SBIN AXISBANK KOTAKBANK INDUSINDBK.
NBFC/Financial: BAJFINANCE SHRIRAMFIN MUTHOOTFIN HDFCAMC SBILIFE BSE.
IT: TCS INFY HCLTECH WIPRO TECHM.
Auto: MARUTI M&M EICHERMOT BAJAJ-AUTO TVSMOTOR.
FMCG/Consumer: HINDUNILVR ITC NESTLEIND BRITANNIA TATACONSUM VBL DMART.
Energy/Power: RELIANCE ONGC COALINDIA NTPC POWERGRID TATAPOWER.
Telecom: BHARTIARTL INDUSTOWER.
Pharma/Healthcare: SUNPHARMA DRREDDY CIPLA DIVISLAB APOLLOHOSP.
Capital goods/Defence: LT BEL HAL SIEMENS ABB.
Metals/Mining: TATASTEEL JSWSTEEL HINDALCO VEDL.
Cement: ULTRACEMCO SHREECEM AMBUJACEM DALBHARAT.
Chemicals/Paints: ASIANPAINT PIDILITIND SRF DEEPAKNTR PIIND.
Real estate: DLF GODREJPROP PRESTIGE.
Consumer durables: TITAN HAVELLS DIXON.
Consumer services: TRENT ETERNAL INDHOTEL INDIGO.
Logistics/Ports: ADANIPORTS CONCOR. Textiles: KPRMILL PAGEIND. Market infra: CAMS.
Media: SUNTV PVRINOX. Infra/Rail: RVNL IRCON. Travel: IRCTC.
Each instrument has exactly one learning profile (business, drivers, risks).

## 6. API overview (all under /api/v1, HTTP Basic unless noted)
auth/register (public) - account/me - market/instruments, quotes, candles, screener -
orders (place, preview, modify PUT, cancel POST, history, detail) - trades - portfolio/me -
risk/me - watchlists - alerts - notifications - learning/companies, overview, compare -
strategy-lab/backtests - journal - performance/me - admin/** (ROLE_ADMIN: overview, users,
orders, market-status, audit-logs, risk-limits, market-calendar, market-data/backfill).
Registered users get role USER. There is no admin-creation endpoint: set the role by SQL.
A static dev user (tradecore.security) also exists for local development.

## 7. Frontend routes
/ /login /register /markets /companies/[symbol] /watchlists /notifications /orders
/portfolio /performance /compare /strategy-lab /journal /risk /admin /admin/audit-logs
/admin/risk-limits. Credentials are currently kept in React memory only (refresh = logout).

## 8. Database
Flyway V1 core schema, V2 invariants, V3 instrument seed, V4 provider timestamps,
V5 order fund reservations, V6 price alerts, V7/V8 learning profiles, V9 trade journal,
V10 trigger price / STOP_MARKET, V11 calendar activation, V12 NSE 2026 holidays.
Never edit these. Next free version: check `db/migration` (V13 unless PROGRESS says otherwise).

## 9. Known defects found in the Oct 2026 audit (check PROGRESS.md: some may be fixed)
1. Ingestion always stores market_status UNKNOWN but placement (MARKET) and execution
   require OPEN, so real data never executes (tests insert OPEN by SQL) - task C1.
2. Execution scheduler scans only the oldest 100 PENDING orders; square-off starts only
   at 15:30 when quotes stop refreshing - task C2.
3. Ingestion requires exactly 80 tradable instruments; no completeness proof; an older
   quote could overwrite a newer one - task C3.
4. No financial reconciliation check - task C4.
5. Admin write actions not audited; WebSocket allows any origin; no explicit CORS; dev
   user always on; no failed-login throttle - task S1.
6. No production profile; client IP behind the Next.js proxy breaks IP rate limits - S2.
7. Password in JS memory, refresh logs the user out - S3.
8. Frontend: var(--border) undefined, .primary-button unstyled, U+FFFD characters in
   markets page, no Idempotency-Key header - task F1.
9. No order-events API, no fill notifications, no market open/closed indicator - F2.
10. No OpenAPI docs, CI, deployment guide; README thin - Q1, Q4, Q5, Q6.

## 10. Out of scope (do not add)
Partial fills, short selling, options/futures, real broker/payments/KYC, microservices,
Kafka, Kubernetes, mobile app, AI predictions, chat/social, more than 80 companies.
