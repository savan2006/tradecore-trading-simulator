# TradeCore

TradeCore is a student project for learning how a trading platform works. It offers a virtual trading account, market information, and tools for reviewing trading activity. It does not place real trades.

## Technologies

- Java 21, Spring Boot, and Maven
- PostgreSQL for application and trading data
- Redis for market-data caching and lightweight rate limiting
- Next.js, React, and TypeScript
- NSE MCP for market data

## How it works

The backend requests market data through NSE MCP, normalizes it, and persists quotes in PostgreSQL. The app serves persisted quotes to the frontend, with Redis caching and WebSocket updates for market quotes. Quote freshness is shown as live, stale, or unavailable.

Users place paper orders against their virtual account. Orders reserve funds or sellable quantity, then simulated execution updates positions and portfolio values. Users can review orders, trades, P&L, watchlists, price alerts, notifications, risk settings, and performance, and keep journal notes on completed trades.

TradeCore includes a supported learning universe of 80 NSE companies, with company profiles available alongside market information. Admin-only operational pages and audit logs are also included.

## Run locally

Set up the environment from the repository root using `.env.example` and fill in the PostgreSQL and Redis connection values required by the backend. Do not commit `.env` or real credentials.

Start the backend from `backend/` with Java 21 and Maven:

```powershell
mvn spring-boot:run
```

Start the frontend from `frontend/`:

```powershell
npm install
npm run dev
```

The frontend API proxy uses `http://localhost:8080` by default; `TRADECORE_BACKEND_URL` can point it at another backend URL.

## Project status

The main backend and frontend learning workflows are implemented and the project is still under development. Trading is simulated with virtual funds; this is not a brokerage or real-money trading system.
